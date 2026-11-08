package brama.claim;

import brama.Brama;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * The small amount of reflection the claim providers need.
 *
 * <p>Reflection rather than a compile-time dependency, deliberately. Compiling against FTB Chunks or
 * Open Parties and Claims would mean a Gradle dependency on each, a matching version for every
 * Minecraft release, and a mod jar that fails to load if either changes shape. None of that is worth
 * it for two questions, and a mod that is not installed has to be handled at runtime regardless.
 *
 * <p>The price is that a provider can be wrong about the version it finds, so every lookup here is
 * nullable, every call is wrapped, and a provider that misses anything it needs reports itself
 * unavailable instead of guessing. Methods are matched by <em>name and argument count</em> rather
 * than by exact signature, because the signatures are what tend to drift between versions while the
 * names stay put.
 */
final class Reflect {

    private Reflect() {
    }

    /** @return the class, or null when it is not on the classpath. */
    static Class<?> type(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * First public method on {@code owner} taking {@code arity} arguments and called by any of
     * {@code names}, in the order given. Inherited methods count.
     *
     * @return the method, or null when none matches
     */
    static Method method(Class<?> owner, int arity, String... names) {
        if (owner == null) {
            return null;
        }
        for (String name : names) {
            for (Method candidate : owner.getMethods()) {
                if (candidate.getName().equals(name) && candidate.getParameterCount() == arity) {
                    candidate.setAccessible(true);
                    return candidate;
                }
            }
        }
        return null;
    }

    /** First public constructor taking {@code arity} arguments. */
    static Constructor<?> constructor(Class<?> owner, int arity) {
        if (owner == null) {
            return null;
        }
        for (Constructor<?> candidate : owner.getConstructors()) {
            if (candidate.getParameterCount() == arity) {
                candidate.setAccessible(true);
                return candidate;
            }
        }
        return null;
    }

    /**
     * Builds an instance of {@code owner} from whichever public constructor accepts {@code args}.
     *
     * <p>For the value types these APIs take as keys — a chunk position, say — where the same type
     * has been spelled {@code (dimension, x, z)} in one version and {@code (level, blockPos)} in
     * another. The caller offers each shape it knows and the first that fits is used.
     *
     * @return the instance, or null when nothing fits
     */
    static Object instantiate(Class<?> owner, Object... args) {
        if (owner == null) {
            return null;
        }
        for (Constructor<?> candidate : owner.getConstructors()) {
            if (!accepts(candidate.getParameterTypes(), args)) {
                continue;
            }
            try {
                candidate.setAccessible(true);
                return candidate.newInstance(args);
            } catch (Throwable thrown) {
                Brama.LOGGER.debug("brama: claim lookup constructor failed", thrown);
            }
        }
        return null;
    }

    private static boolean accepts(Class<?>[] parameters, Object[] args) {
        if (parameters.length != args.length) {
            return false;
        }
        for (int i = 0; i < parameters.length; i++) {
            if (args[i] == null) {
                continue;
            }
            Class<?> wanted = parameters[i].isPrimitive() ? boxed(parameters[i]) : parameters[i];
            if (!wanted.isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> boxed(Class<?> primitive) {
        if (primitive == int.class) {
            return Integer.class;
        }
        if (primitive == long.class) {
            return Long.class;
        }
        if (primitive == boolean.class) {
            return Boolean.class;
        }
        if (primitive == double.class) {
            return Double.class;
        }
        if (primitive == float.class) {
            return Float.class;
        }
        return primitive;
    }

    /** @return the result, or null when the method is missing or the call threw. */
    static Object call(Method method, Object target, Object... args) {
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(target, args);
        } catch (Throwable thrown) {
            Brama.LOGGER.debug("brama: claim lookup call {} failed", method.getName(), thrown);
            return null;
        }
    }

    static Object construct(Constructor<?> constructor, Object... args) {
        if (constructor == null) {
            return null;
        }
        try {
            return constructor.newInstance(args);
        } catch (Throwable thrown) {
            Brama.LOGGER.debug("brama: claim lookup constructor failed", thrown);
            return null;
        }
    }

    /** True when a method returning a boolean said so. Anything else — missing, threw — is false. */
    static boolean flag(Method method, Object target, Object... args) {
        return call(method, target, args) instanceof Boolean value && value;
    }

    /**
     * Unwraps whatever an API hands back for "maybe a team": these APIs variously return the thing,
     * null, or an {@link Optional} of it.
     */
    static Object unwrap(Object value) {
        if (value instanceof Optional<?> optional) {
            return optional.orElse(null);
        }
        return value;
    }
}

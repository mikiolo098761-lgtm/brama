package brama.claim;

import brama.Brama;
import brama.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

/**
 * The one door between this mod and whatever claim mod the server runs.
 *
 * <p>Nothing outside this package names a claim mod, and nothing inside it is compiled against one.
 * The rest of the mod asks two questions — is this player an intruder here, and are these two
 * allies — and gets an answer from whichever {@link ClaimLookup} the server turned out to have.
 *
 * <p>Picking one is deliberately late and deliberately forgiving. Late, because mod configs are read
 * long after mods are loaded, so the choice is made on first use and not at startup. Forgiving,
 * because a provider that finds its mod installed but unrecognisable retires itself, and the next
 * question picks another one — which is how a server survives a claim mod updating out from under
 * this one.
 */
public final class ClaimCompat {

    private static final String AUTO = "auto";
    private static final String NONE = "none";

    @Nullable
    private static ClaimLookup active;

    private ClaimCompat() {
    }

    /** Called when a server shuts down, so a single-player session does not carry state to the next. */
    public static void reset() {
        active = null;
    }

    /** Whether {@code player} has walked onto land the gate's owner holds. */
    public static ClaimLookup.Verdict intruderAt(ServerLevel level, BlockPos pos, ServerPlayer player,
                                                 @Nullable UUID owner) {
        if (owner == null || owner.equals(player.getUUID())) {
            return ClaimLookup.Verdict.WELCOME;
        }
        ClaimLookup lookup = lookup();
        return lookup == null ? ClaimLookup.Verdict.UNKNOWN : lookup.intruderAt(level, pos, player, owner);
    }

    /**
     * Whether the claim mod counts these two as on the same side — a teammate or a declared ally.
     *
     * <p>Takes the loose types the block entity has to hand, and answers false for anything that is
     * not a player on a server: every permission check that matters runs server-side.
     */
    public static boolean areAllies(@Nullable Level level, @Nullable UUID owner, Player player) {
        if (owner == null || !(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        if (owner.equals(player.getUUID())) {
            return true;
        }
        ClaimLookup lookup = lookup();
        return lookup != null && lookup.areAllies(serverLevel, owner, serverPlayer);
    }

    /** The lookup in force, choosing one if this is the first question since the server came up. */
    @Nullable
    private static synchronized ClaimLookup lookup() {
        if (active != null && active.available()) {
            return active;
        }
        // A retired provider is not asked again: choose() skips it, because its own available() is
        // what reports the retirement.
        active = choose();
        return active;
    }

    @Nullable
    private static ClaimLookup choose() {
        String configured = Config.claimProvider().trim().toLowerCase(Locale.ROOT);

        if (NONE.equals(configured)) {
            return null;
        }
        if (ProbeLookup.ID.equals(configured)) {
            return new ProbeLookup();
        }

        if (FtbChunksLookup.ID.equals(configured) || (AUTO.equals(configured) && loaded(FtbChunksLookup.ID))) {
            ClaimLookup ftb = new FtbChunksLookup();
            if (ftb.available()) {
                return ftb;
            }
            return new ProbeLookup();
        }
        if (OpacLookup.ID.equals(configured) || (AUTO.equals(configured) && loaded(OpacLookup.ID))) {
            ClaimLookup opac = new OpacLookup();
            if (opac.available()) {
                return opac;
            }
            return new ProbeLookup();
        }

        if (!AUTO.equals(configured)) {
            // Flan and the rest have no provider of their own: the probe already understands them,
            // because it asks in the language every protection mod speaks.
            Brama.LOGGER.info("brama: no built-in claim provider called '{}'; using the generic probe, "
                    + "which works with most protection mods", configured);
        }
        return new ProbeLookup();
    }

    private static boolean loaded(String modId) {
        return ModList.get() != null && ModList.get().isLoaded(modId);
    }
}

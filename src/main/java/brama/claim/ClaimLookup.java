package brama.claim;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;

import java.util.UUID;

/**
 * One claim mod, reduced to the two questions this mod has for it.
 *
 * <p>Both questions are about people, not about land: a gate does not care where the boundary runs,
 * only whether the figure walking towards it has any business being there. Everything a claim mod
 * knows that does not answer one of those two questions is ignored.
 */
public interface ClaimLookup {

    /** Matches the {@code claimProvider} config value that selects this lookup. */
    String id();

    /**
     * Whether this lookup can currently answer anything. False when the mod is absent, and also once
     * a query has failed — these are bound by reflection against mods this one does not compile
     * against, so a provider that turns out not to fit the installed version retires itself rather
     * than failing once a second forever.
     */
    boolean available();

    /**
     * Whether {@code player} has walked into land the gate's owner holds.
     *
     * @param owner the gate's owner, or null for an unowned gate
     */
    Verdict intruderAt(ServerLevel level, BlockPos pos, ServerPlayer player, UUID owner);

    /**
     * Whether {@code player} is a teammate or an ally of {@code owner}, wherever either of them is
     * standing. This is what lets an alliance signed in the claim mod's own menus open a gate.
     *
     * @return false when the mod cannot say, which costs an ally nothing but a {@code /gate trust}
     */
    default boolean areAllies(ServerLevel level, UUID owner, ServerPlayer player) {
        return false;
    }

    /** What a claim mod makes of one player standing in one place. */
    enum Verdict {
        /** On the owner's land, and not welcome on it. */
        INTRUDER,
        /** On the owner's land and entitled to be — a member of the team, or an ally of it. */
        WELCOME,
        /**
         * No opinion: unclaimed land, land belonging to a third party, or a question this mod cannot
         * answer. The gate then falls back to the owner's own enemy list, and to
         * {@code closeOnStrangers}.
         */
        UNKNOWN
    }
}

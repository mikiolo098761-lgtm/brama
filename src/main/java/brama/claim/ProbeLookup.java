package brama.claim;

import brama.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.Event;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The claim lookup that knows nothing about any claim mod, and works with all of them.
 *
 * <p>Rather than read a mod's claims, it asks the question a claim mod already answers for every
 * block in the world: <em>may this player touch this?</em> It offers the server a cancellable
 * right-click on the gate in the player's name and watches what happens to it. Every protection mod
 * that guards blocks — FTB Chunks, Flan, Open Parties and Claims, GriefPrevention ports, a server's
 * own plugin — refuses an interaction from somebody who does not belong on that claim, so a refusal
 * is as good as being told they are an intruder.
 *
 * <p>Two things it cannot do, both inherent. It knows nothing about <b>alliances</b>: an ally is
 * usually permitted to interact, so they come back as permitted rather than as an ally, which is the
 * right answer for shutting gates and no answer at all for opening them. And an interaction that
 * nothing refuses is reported as {@link Verdict#UNKNOWN} rather than as welcome, because silence here
 * means either "allowed" or "nothing is watching this ground", and those are not the same thing.
 *
 * <p>The cost is that other mods see an interaction that never happened. A grief logger will log it,
 * and a mod that acts on right-clicks in the event rather than in the block could in principle act on
 * it. That is why it is throttled hard — {@code siege.probeInterval}, five seconds by default, per
 * player and gate — and why a server with a supported claim mod gets that mod's own API instead.
 */
final class ProbeLookup implements ClaimLookup {

    static final String ID = "probe";

    /** One cached answer, so a player loitering outside a gate is not probed every sweep. */
    private record Answer(long tick, Verdict verdict) {
    }

    private final Map<Long, Answer> answers = new HashMap<>();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Verdict intruderAt(ServerLevel level, BlockPos pos, ServerPlayer player, UUID owner) {
        long key = key(player.getUUID(), pos);
        long now = level.getGameTime();

        Answer cached = this.answers.get(key);
        if (cached != null && now - cached.tick() < Config.probeInterval()) {
            return cached.verdict();
        }

        Verdict verdict = probe(level, pos, player);
        this.answers.put(key, new Answer(now, verdict));
        forget(now);
        return verdict;
    }

    private Verdict probe(ServerLevel level, BlockPos pos, ServerPlayer player) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        PlayerInteractEvent.RightClickBlock event =
                ForgeHooks.onRightClickBlock(player, InteractionHand.MAIN_HAND, pos, hit);

        boolean refused = event.isCanceled() || event.getUseBlock() == Event.Result.DENY;
        return refused ? Verdict.INTRUDER : Verdict.UNKNOWN;
    }

    /**
     * Drops answers older than ten times the throttle. Nothing cares about them any more, and a
     * server that has been up for a month should not be holding a probe result for every player who
     * has ever walked past a gate.
     */
    private void forget(long now) {
        long stale = Config.probeInterval() * 10L;
        this.answers.entrySet().removeIf(entry -> now - entry.getValue().tick() > stale);
    }

    /** Player and gate in one long: the low bits of the player's id against the block position. */
    private static long key(UUID player, BlockPos pos) {
        return player.getLeastSignificantBits() * 31L + pos.asLong();
    }
}

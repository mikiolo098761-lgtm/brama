package brama.siege;

import brama.Brama;
import brama.Config;
import brama.block.AbstractGateBlock;
import brama.block.GateAssembly;
import brama.block.GateSize;
import brama.blockentity.GateBlockEntity;
import brama.claim.ClaimCompat;
import brama.claim.ClaimLookup;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Gates that watch their own gateway: an enemy walks up to an open gate and it shuts in their face.
 *
 * <p>The sweep walks the <b>players</b>, not the gates. A castle is a fixed object and there may be
 * hundreds of them on a long-lived server, while the people who could threaten one are however many
 * happen to be logged in — so gates are indexed by chunk as they load, and each player looks up only
 * the handful of chunks within earshot of them. The cost of the feature then follows the player count
 * and not the amount anybody has built.
 *
 * <p>It also carries the one-off job of measuring gates built before the footprint moved onto the
 * block entity. That work needs the blocks around the origin to be loaded, which they are not while
 * the origin's own chunk is still loading, so it waits for a tick where it can look safely — the same
 * reason it lives here rather than in the block entity.
 */
public final class GateWatch {

    /** How close a defender has to be to hear about it, in blocks. */
    private static final double WARN_RADIUS = 64.0D;
    /** Ticks before a gate will warn its owner about an enemy again. */
    private static final long WARN_INTERVAL = 200L;
    /**
     * Sweeps a gate gets to have its footprint measured before it is written off. A gate straddling
     * a chunk border has to wait for the neighbour to load, which may be several ticks; one that is
     * genuinely broken never succeeds, and is taken apart into loose blocks rather than left
     * standing as the wrong shape.
     */
    private static final int MEASURE_ATTEMPTS = 20;

    /** Origin positions of every loaded gate, bucketed by the chunk they stand in. */
    private static final Map<ResourceKey<Level>, Map<Long, Set<BlockPos>>> GATES = new HashMap<>();
    /** Gates still waiting to be measured, with the number of sweeps each has had. */
    private static final Map<ResourceKey<Level>, Map<BlockPos, Integer>> PENDING = new HashMap<>();
    /** When each gate last warned its defenders, so a besieger camped outside does not spam them. */
    private static final Map<BlockPos, Long> WARNED = new HashMap<>();

    private GateWatch() {
    }

    // ----------------------------------------------------------- bookkeeping

    public static void gateLoaded(ServerLevel level, GateBlockEntity gate) {
        BlockPos origin = gate.getBlockPos().immutable();
        GATES.computeIfAbsent(level.dimension(), key -> new HashMap<>())
                .computeIfAbsent(ChunkPos.asLong(origin), key -> new HashSet<>())
                .add(origin);

        if (gate.needsSizeMeasured()) {
            PENDING.computeIfAbsent(level.dimension(), key -> new HashMap<>()).put(origin, 0);
        }
    }

    public static void gateUnloaded(ServerLevel level, BlockPos origin) {
        forget(level.dimension(), origin);
    }

    private static void forget(ResourceKey<Level> dimension, BlockPos origin) {
        Map<Long, Set<BlockPos>> byChunk = GATES.get(dimension);
        if (byChunk != null) {
            Set<BlockPos> inChunk = byChunk.get(ChunkPos.asLong(origin));
            if (inChunk != null) {
                inChunk.remove(origin);
                if (inChunk.isEmpty()) {
                    byChunk.remove(ChunkPos.asLong(origin));
                }
            }
        }
        Map<BlockPos, Integer> pending = PENDING.get(dimension);
        if (pending != null) {
            pending.remove(origin);
        }
        WARNED.remove(origin);
    }

    /** Nothing survives a server stopping: a single-player world must not inherit the last one. */
    public static void serverStopped() {
        GATES.clear();
        PENDING.clear();
        WARNED.clear();
        ClaimCompat.reset();
    }

    public static void levelUnloaded(ServerLevel level) {
        GATES.remove(level.dimension());
        PENDING.remove(level.dimension());
    }

    // ------------------------------------------------------------------ tick

    public static void tick(ServerLevel level) {
        measurePending(level);

        if (!Config.autoClose()) {
            return;
        }
        long now = level.getGameTime();
        if (now % Config.scanInterval() != 0L) {
            return;
        }
        sweep(level, now);
    }

    private static void sweep(ServerLevel level, long now) {
        Map<Long, Set<BlockPos>> byChunk = GATES.get(level.dimension());
        if (byChunk == null || byChunk.isEmpty() || level.players().isEmpty()) {
            return;
        }

        double radius = Config.alarmRadius();
        double radiusSqr = radius * radius;
        int span = SectionPos.blockToSectionCoord((int) Math.ceil(radius)) + 1;

        // Collected rather than acted on in place: shutting a gate can take it apart, which would
        // rewrite the very sets being walked.
        List<Threat> threats = new ArrayList<>();

        for (ServerPlayer player : level.players()) {
            // A creative-mode player is administrating or building, and can cross a shut gate
            // anyway; a spectator is not there at all.
            if (player.isSpectator() || player.isCreative()) {
                continue;
            }

            int chunkX = SectionPos.blockToSectionCoord(player.getBlockX());
            int chunkZ = SectionPos.blockToSectionCoord(player.getBlockZ());

            for (int dx = -span; dx <= span; dx++) {
                for (int dz = -span; dz <= span; dz++) {
                    Set<BlockPos> gates = byChunk.get(ChunkPos.asLong(chunkX + dx, chunkZ + dz));
                    if (gates == null) {
                        continue;
                    }
                    for (BlockPos origin : gates) {
                        if (origin.distToCenterSqr(player.position()) <= radiusSqr) {
                            threats.add(new Threat(origin, player));
                        }
                    }
                }
            }
        }

        for (Threat threat : threats) {
            consider(level, threat.origin(), threat.player(), now);
        }
    }

    private record Threat(BlockPos origin, ServerPlayer player) {
    }

    /** One player, one gate: shut it if they have no business being there. */
    private static void consider(ServerLevel level, BlockPos origin, ServerPlayer player, long now) {
        BlockState state = level.getBlockState(origin);
        if (!(state.getBlock() instanceof AbstractGateBlock gate) || !GateAssembly.isOrigin(state)) {
            // The index is also pruned here, so a gate that vanished without a block entity hook ever
            // firing does not linger in it.
            forget(level.dimension(), origin);
            return;
        }
        if (!state.getValue(AbstractGateBlock.OPEN)) {
            return;
        }
        if (!(level.getBlockEntity(origin) instanceof GateBlockEntity blockEntity)) {
            return;
        }

        UUID owner = blockEntity.owner();
        // An unowned gate has nobody to have enemies on behalf of.
        if (owner == null || blockEntity.canOpen(player)) {
            return;
        }
        if (!hostile(level, origin, player, owner)) {
            return;
        }

        if (gate.setOpen(level, origin, state, false, null)) {
            warnDefenders(level, origin, blockEntity, player, now);
        }
    }

    /**
     * The three-step question from the config's own description: the owner's list first, because it
     * is the one answer a player gave deliberately, then the claim mod, then nothing at all unless
     * the server has asked for strangers to count.
     */
    private static boolean hostile(ServerLevel level, BlockPos origin, ServerPlayer player, UUID owner) {
        if (GateEnemies.of(level.getServer()).isEnemy(owner, player.getUUID())) {
            return true;
        }
        ClaimLookup.Verdict verdict = ClaimCompat.intruderAt(level, origin, player, owner);
        return switch (verdict) {
            case INTRUDER -> true;
            case WELCOME -> false;
            case UNKNOWN -> Config.closeOnStrangers();
        };
    }

    private static void warnDefenders(ServerLevel level, BlockPos origin, GateBlockEntity gate,
                                      ServerPlayer enemy, long now) {
        if (!Config.warnDefenders()) {
            return;
        }
        Long last = WARNED.get(origin);
        if (last != null && now - last < WARN_INTERVAL) {
            return;
        }
        WARNED.put(origin.immutable(), now);

        Component message = Component.translatable("message.brama.gate_alarm", enemy.getGameProfile().getName())
                .withStyle(ChatFormatting.RED);
        for (ServerPlayer defender : level.players()) {
            if (defender != enemy
                    && defender.distanceToSqr(origin.getX(), origin.getY(), origin.getZ()) <= WARN_RADIUS * WARN_RADIUS
                    && gate.canOpen(defender)) {
                defender.displayClientMessage(message, false);
            }
        }
    }

    // ------------------------------------------------------- size recovery

    /**
     * Measures gates saved before the footprint moved onto the block entity, a few at a time as their
     * surroundings come into memory.
     */
    private static void measurePending(ServerLevel level) {
        Map<BlockPos, Integer> pending = PENDING.get(level.dimension());
        if (pending == null || pending.isEmpty()) {
            return;
        }

        for (BlockPos origin : new ArrayList<>(pending.keySet())) {
            int attempts = pending.getOrDefault(origin, 0) + 1;

            BlockState state = level.getBlockState(origin);
            if (!(level.getBlockEntity(origin) instanceof GateBlockEntity gate) || !GateAssembly.isOrigin(state)) {
                pending.remove(origin);
                continue;
            }
            if (!gate.needsSizeMeasured()) {
                pending.remove(origin);
                continue;
            }

            GateSize measured = surroundingsLoaded(level, origin)
                    ? GateAssembly.measureAssembled(level, origin, state)
                    : null;

            if (measured != null) {
                gate.setSize(measured);
                pending.remove(origin);
                continue;
            }

            if (attempts >= MEASURE_ATTEMPTS) {
                // Out of patience: a gate of unknown shape would draw and collide as a 3x3, so take
                // it apart into loose blocks instead. Visible, breakable, and one hammer blow from
                // being a gate again.
                Brama.LOGGER.warn("brama: could not work out the footprint of the gate at {} in {}; "
                        + "taking it apart into loose blocks", origin, level.dimension().location());
                GateAssembly.disassemble(level, origin, state, null);
                pending.remove(origin);
                continue;
            }
            pending.put(origin, attempts);
        }
    }

    /**
     * Whether every chunk a gate could reach into is in memory. Measuring reads the blocks around the
     * origin, and a missing neighbour reads as missing parts — which would measure a 7x9 gate as
     * something smaller, or as nothing at all.
     */
    private static boolean surroundingsLoaded(ServerLevel level, BlockPos origin) {
        int reach = Math.max(GateSize.MAX_WIDTH, GateSize.MAX_HEIGHT);
        for (int dx = -reach; dx <= reach; dx += reach) {
            for (int dz = -reach; dz <= reach; dz += reach) {
                if (!level.hasChunkAt(origin.offset(dx, 0, dz))) {
                    return false;
                }
            }
        }
        return true;
    }
}

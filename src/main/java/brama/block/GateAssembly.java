package brama.block;

import brama.Config;
import brama.blockentity.GateBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * All of the multiblock arithmetic, shared by every gate type.
 *
 * <p>A gate is a flat grid of one kind of gate block standing in a vertical plane, in one of the
 * footprints listed by {@link GateSize}. The plane runs along {@link AbstractGateBlock#AXIS}; the
 * "origin" is the part at {@code part_h = 0, part_v = 0}, i.e. the bottom corner with the lowest
 * coordinate along that axis. Only the origin carries a BlockEntity, and every other part can find it
 * by pure arithmetic from its own blockstate.
 *
 * <p>Nothing here names a specific block. Operations take a reference {@link BlockState} and derive
 * the block and axis from it, so a frame is always made of one uniform gate type — and since each
 * wood is its own block, of one uniform wood.
 */
public final class GateAssembly {

    /** Thickness of a closed gate panel, in blocks. Shared by the collision shape and the renderer. */
    public static final float THICKNESS = 0.25F;

    private static final Direction.Axis[] HORIZONTAL_AXES = { Direction.Axis.X, Direction.Axis.Z };

    /**
     * Re-entrancy guard, so a disassemble cannot bounce straight back into an assemble while it is
     * still rewriting parts.
     */
    private static boolean updating;

    /**
     * Debounce state for {@link #advanceDamageOnce}: which tick we are counting for, and every gate
     * already charged a stage during it.
     *
     * <p>A set rather than a single slot. One blast can reach two gates — a gatehouse with an inner
     * and an outer door, say — and remembering only the most recent one let the parts interleave: the
     * second gate evicted the first, so the first's remaining blocks were no longer recognised as
     * repeats and charged it all over again.
     */
    private static long lastDamageTick = Long.MIN_VALUE;
    private static final Set<BlockPos> damagedThisTick = new HashSet<>();

    /**
     * Blockstate change flags used for every internal gate update. Deliberately omits
     * {@code UPDATE_NEIGHBORS}: assembling and opening rewrite every part in a loop, and firing
     * neighbour updates in the middle of that would re-enter {@link #tryAssemble} on half-written
     * gates.
     */
    public static final int GATE_UPDATE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE;

    private GateAssembly() {
    }

    /**
     * True while gate parts are being rewritten in bulk. {@code AbstractGateBlock#onRemove} consults
     * this so that clearing a destroyed gate to air does not trip its disassembly branch and race
     * the destruction half-way through.
     */
    public static boolean isUpdating() {
        return updating;
    }

    /**
     * Runs a bulk rewrite of gate parts under the {@link #isUpdating()} guard, so the block removals
     * it performs are not mistaken for an outside force by {@code onRemove}.
     *
     * <p>Saves and restores rather than clearing, so it nests safely inside another guarded block.
     */
    public static void runGuarded(Runnable action) {
        boolean previous = updating;
        updating = true;
        try {
            action.run();
        } finally {
            updating = previous;
        }
    }

    /** The direction the gate widens in as {@code part_h} increases. */
    public static Direction widthDirection(Direction.Axis axis) {
        return Direction.get(Direction.AxisDirection.POSITIVE, axis);
    }

    /** The horizontal axis the gate opens along, i.e. the one it blocks passage on. */
    public static Direction.Axis perpendicularAxis(Direction.Axis axis) {
        return axis == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
    }

    public static boolean isOrigin(BlockState state) {
        return state.getBlock() instanceof AbstractGateBlock
                && state.getValue(AbstractGateBlock.ASSEMBLED)
                && state.getValue(AbstractGateBlock.PART_H) == 0
                && state.getValue(AbstractGateBlock.PART_V) == 0;
    }

    /**
     * The footprint of the gate whose origin stands at {@code origin}, read from its block entity.
     *
     * <p>Falls back to the smallest square rather than refusing, because every caller is mid-way
     * through an operation on a gate that demonstrably exists. A gate whose block entity has gone is
     * already handled: {@code neighborChanged} takes it apart into loose blocks.
     */
    public static GateSize sizeOf(BlockGetter level, BlockPos origin) {
        return level.getBlockEntity(origin) instanceof GateBlockEntity gate ? gate.size() : GateSize.SIZE_3X3;
    }

    /**
     * Whether the origin this part's blockstate points at really is the origin of an assembled gate
     * of the same type.
     *
     * <p>False means the part is an orphan: still flagged assembled, but with nothing left to draw
     * it, which is the invisible-wall failure mode.
     */
    public static boolean hasValidOrigin(Level level, BlockPos pos, BlockState state) {
        BlockState origin = level.getBlockState(originOf(state, pos));
        return origin.is(state.getBlock()) && isOrigin(origin);
    }

    /**
     * Walks back from any part to the position of its gate's origin.
     *
     * <p>Delegates to the gate type, because a lowered drawbridge lays its parts out flat rather
     * than in the vertical plane the others use.
     */
    public static BlockPos originOf(BlockState state, BlockPos pos) {
        return state.getBlock() instanceof AbstractGateBlock gate ? gate.originOf(state, pos) : pos;
    }

    /** Where part {@code (h, v)} of the gate described by {@code reference} sits. */
    public static BlockPos partPos(BlockState reference, BlockPos origin, int h, int v) {
        return ((AbstractGateBlock) reference.getBlock()).partPos(reference, origin, h, v);
    }

    /**
     * The vertical layout, used while assembling. Every gate type is built as a vertical plane, so
     * assembly cannot ask the block where its parts go — there is no assembled state yet to ask about.
     */
    public static BlockPos verticalPartPos(BlockPos origin, Direction.Axis axis, int h, int v) {
        return origin.above(v).relative(widthDirection(axis), h);
    }

    // -------------------------------------------------------------- assembly

    /**
     * Links the loose gate blocks around {@code pos} into a gate, if they form one of the supported
     * footprints. Tries every enabled {@link GateSize}, every orientation, and every origin offset
     * that would place this block somewhere in the grid.
     *
     * @param owner the player wielding the hammer, who becomes the gate's owner. Null for any other
     *              caller, which leaves the gate unowned and therefore open to everyone.
     */
    public static boolean tryAssemble(Level level, BlockPos pos, @Nullable Player owner) {
        if (level.isClientSide || updating) {
            return false;
        }

        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof AbstractGateBlock gate) || state.getValue(AbstractGateBlock.ASSEMBLED)) {
            return false;
        }

        // Biggest footprint first -- see GateSize.LARGEST_FIRST for why the order matters. The
        // config filter must not disturb that ordering.
        for (GateSize size : GateSize.LARGEST_FIRST) {
            if (!Config.sizeEnabled(size)) {
                continue;
            }
            // Search both orientations rather than trusting the block's own axis. That property
            // comes from whichever way the player was facing when each block went down, so a
            // hand-built gate very often contains a mix of both.
            for (Direction.Axis axis : HORIZONTAL_AXES) {
                Direction width = widthDirection(axis);
                for (int v = 0; v < size.height(); v++) {
                    for (int h = 0; h < size.width(); h++) {
                        BlockPos origin = pos.below(v).relative(width.getOpposite(), h);
                        if (isCompleteLooseFrame(level, origin, axis, size, gate)) {
                            assembleAt(level, origin, axis, size, gate, owner);
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    /** Every cell must be the same gate block, so wooden and portcullis parts cannot be mixed. */
    private static boolean isCompleteLooseFrame(Level level, BlockPos origin, Direction.Axis axis,
                                                GateSize size, AbstractGateBlock gate) {
        for (int v = 0; v < size.height(); v++) {
            for (int h = 0; h < size.width(); h++) {
                BlockState part = level.getBlockState(verticalPartPos(origin, axis, h, v));
                if (!part.is(gate) || part.getValue(AbstractGateBlock.ASSEMBLED)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void assembleAt(Level level, BlockPos origin, Direction.Axis axis, GateSize size,
                                   AbstractGateBlock gate, @Nullable Player owner) {
        // A gate has a single damage level, so a mixed-condition pile of blocks takes the worst one
        // present rather than averaging or refusing.
        int worstDamage = 0;
        for (int v = 0; v < size.height(); v++) {
            for (int h = 0; h < size.width(); h++) {
                BlockState part = level.getBlockState(verticalPartPos(origin, axis, h, v));
                worstDamage = Math.max(worstDamage, part.getValue(AbstractGateBlock.DAMAGE));
            }
        }
        int damage = Math.min(worstDamage, gate.maxDamage());

        // Gate-type state decided once at assembly, e.g. which way a drawbridge deck will fall.
        UnaryOperator<BlockState> decorator = UnaryOperator.identity();
        if (owner != null) {
            BlockState reference = gate.defaultBlockState().setValue(AbstractGateBlock.AXIS, axis);
            decorator = gate.assemblyDecorator(level, origin, reference, owner);
        }

        UnaryOperator<BlockState> decorate = decorator;
        runGuarded(() -> {
            for (int v = 0; v < size.height(); v++) {
                for (int h = 0; h < size.width(); h++) {
                    BlockPos partPos = verticalPartPos(origin, axis, h, v);
                    // AXIS is normalised here, so the mixed axes a hand-built gate arrives with all
                    // get rewritten to the plane that actually matched.
                    BlockState assembled = level.getBlockState(partPos)
                            .setValue(AbstractGateBlock.AXIS, axis)
                            .setValue(AbstractGateBlock.ASSEMBLED, true)
                            .setValue(AbstractGateBlock.PART_H, h)
                            .setValue(AbstractGateBlock.PART_V, v)
                            .setValue(AbstractGateBlock.DAMAGE, damage)
                            .setValue(AbstractGateBlock.OPEN, false);
                    level.setBlock(partPos, decorate.apply(assembled), GATE_UPDATE_FLAGS);
                }
            }
        });

        // The block entity only exists once the origin has been written above. The footprint goes on
        // it unconditionally -- the gate is unusable without it, owned or not.
        if (level.getBlockEntity(origin) instanceof GateBlockEntity blockEntity) {
            blockEntity.setSize(size);
            if (owner != null) {
                blockEntity.setOwner(owner);
            }
        }

        // Audible confirmation that the blocks actually linked up. Without it there is no way to
        // tell an assembled gate from a stack of loose blocks until you try to open it.
        level.playSound(null, verticalPartPos(origin, axis, size.width() / 2, size.height() / 2),
                gate.assembleSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
    }

    /**
     * Works out the footprint of an already-assembled gate from the part indices of the blocks around
     * its origin, for gates built before the footprint moved onto the block entity.
     *
     * <p>Those gates kept it in the blockstate, and a property that no longer exists is dropped on
     * load — so the size has to be reconstructed from what survived, which is {@code part_h} and
     * {@code part_v} on every part. The largest footprint whose every cell is present wins, for the
     * same reason assembly searches largest-first: a 5x5's parts contain a complete 3x3 in the
     * corner, so checking small sizes first would under-measure every big gate.
     *
     * @return the footprint, or null when the parts fit none — a gate half-mined while the server was
     *         down, say, which the caller then takes apart into loose blocks.
     */
    @Nullable
    public static GateSize measureAssembled(Level level, BlockPos origin, BlockState originState) {
        if (!(originState.getBlock() instanceof AbstractGateBlock gate)) {
            return null;
        }

        for (GateSize size : GateSize.LARGEST_FIRST) {
            if (isCompleteAssembledFrame(level, origin, originState, gate, size)) {
                return size;
            }
        }
        return null;
    }

    private static boolean isCompleteAssembledFrame(Level level, BlockPos origin, BlockState reference,
                                                    AbstractGateBlock gate, GateSize size) {
        for (int v = 0; v < size.height(); v++) {
            for (int h = 0; h < size.width(); h++) {
                BlockState part = level.getBlockState(gate.partPos(reference, origin, h, v));
                if (!part.is(gate)
                        || !part.getValue(AbstractGateBlock.ASSEMBLED)
                        || part.getValue(AbstractGateBlock.PART_H) != h
                        || part.getValue(AbstractGateBlock.PART_V) != v) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Breaks the link between the remaining parts of a gate, turning them back into loose blocks.
     *
     * @param reference  any part of the gate; supplies the block and axis
     * @param removedPos the part that was destroyed and should be skipped, or null to reset every
     *                   part (which is what the hammer does when taking a gate apart by hand)
     */
    public static void disassemble(Level level, BlockPos origin, BlockState reference, @Nullable BlockPos removedPos) {
        if (level.isClientSide) {
            return;
        }

        updating = true;
        try {
            forEachAssembledPart(level, origin, reference, (partPos, part) -> {
                if (partPos.equals(removedPos)) {
                    return;
                }
                level.setBlock(partPos, part
                        .setValue(AbstractGateBlock.ASSEMBLED, false)
                        .setValue(AbstractGateBlock.PART_H, 0)
                        .setValue(AbstractGateBlock.PART_V, 0)
                        .setValue(AbstractGateBlock.OPEN, false), GATE_UPDATE_FLAGS);
            });
        } finally {
            updating = false;
        }
    }

    // ---------------------------------------------------------------- damage

    /**
     * Advances the damage stage by one. Damage belongs to the gate as a whole, so striking any part
     * of an assembled gate degrades all of it; a loose block only degrades itself.
     */
    public static void advanceDamage(Level level, BlockPos pos, BlockState state) {
        if (level.isClientSide || !(state.getBlock() instanceof AbstractGateBlock gate)) {
            return;
        }

        int next = Math.min(gate.maxDamage(), state.getValue(AbstractGateBlock.DAMAGE) + 1);

        if (!state.getValue(AbstractGateBlock.ASSEMBLED)) {
            level.setBlock(pos, state.setValue(AbstractGateBlock.DAMAGE, next), GATE_UPDATE_FLAGS);
            return;
        }

        BlockPos origin = originOf(state, pos);

        updating = true;
        try {
            forEachAssembledPart(level, origin, state, (partPos, part) ->
                    level.setBlock(partPos, part.setValue(AbstractGateBlock.DAMAGE, next), GATE_UPDATE_FLAGS));
        } finally {
            updating = false;
        }
    }

    /**
     * As {@link #advanceDamage}, but at most once per gate per tick.
     *
     * <p>Needed because explosions and multi-block breakers hit every part separately: one TNT beside
     * a 5x7 gate would otherwise call this 35 times and blow through every stage at once.
     */
    public static void advanceDamageOnce(Level level, BlockPos pos, BlockState state) {
        if (level.isClientSide) {
            return;
        }

        BlockPos key = state.getValue(AbstractGateBlock.ASSEMBLED) ? originOf(state, pos) : pos;
        long now = level.getGameTime();
        if (now != lastDamageTick) {
            lastDamageTick = now;
            damagedThisTick.clear();
        }
        if (!damagedThisTick.add(key.immutable())) {
            return;
        }

        advanceDamage(level, pos, state);
    }

    /**
     * Clears every part of an assembled gate.
     *
     * <p>The {@link #updating} flag is essential here, and not only to stop a disassembly starting
     * half-way through the teardown: with the whole teardown guarded, any <em>unguarded</em> removal
     * of an assembled part must have come from outside the mod, which is what lets
     * {@link #handleExternalBreak} identify a battering ram without knowing anything about it.
     */
    public static void destroyGate(Level level, BlockPos strikePos, BlockState state, boolean dropItems,
                                   boolean skipStrikePos) {
        if (level.isClientSide || !state.getValue(AbstractGateBlock.ASSEMBLED)) {
            return;
        }

        BlockPos origin = originOf(state, strikePos);

        updating = true;
        try {
            forEachAssembledPart(level, origin, state, (partPos, part) -> {
                if (skipStrikePos && partPos.equals(strikePos)) {
                    return;
                }
                if (dropItems) {
                    Block.dropResources(part, level, partPos, level.getBlockEntity(partPos));
                }
                // Break particles and sound at each part, so a big gate visibly comes down.
                level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, partPos, Block.getId(part));
                level.removeBlock(partPos, false);
            });
        } finally {
            updating = false;
        }
    }

    /**
     * Handles a gate part removed by something that is not this mod and not the player-breaking path
     * — a battering ram, WorldEdit, another mod's block clearer.
     *
     * <p>Below the last stage the block is put straight back and the gate takes a stage instead, so a
     * ram needs the same number of hits a player does. At the last stage the gate comes down for real.
     *
     * <p>The restore is a nested {@code setBlock} from inside {@code onRemove}. Vanilla anticipates
     * this — {@code LevelChunk.setBlockState} bails out when the block changes underneath it — the
     * only visible effect being that the breaker's own call reports failure.
     */
    public static void handleExternalBreak(Level level, BlockPos pos, BlockState state) {
        if (level.isClientSide || updating
                || !(state.getBlock() instanceof AbstractGateBlock gate)
                || !state.getValue(AbstractGateBlock.ASSEMBLED)) {
            return;
        }

        // Opt-out: restore the old behaviour of quietly disassembling into loose blocks.
        if (!Config.externalBreaksDamageGates()) {
            disassemble(level, originOf(state, pos), state, pos);
            return;
        }

        if (gate.isCritical(state)) {
            destroyGate(level, pos, state, false, false);
            return;
        }

        updating = true;
        try {
            level.setBlock(pos, state, GATE_UPDATE_FLAGS);
        } finally {
            updating = false;
        }
        advanceDamageOnce(level, pos, state);
    }

    // ------------------------------------------------------------------ open

    /**
     * Applies a new open state to every part at once.
     *
     * @param decorator extra per-part state supplied by the gate type, e.g. the swing direction
     */
    public static void setOpen(Level level, BlockPos origin, BlockState reference, boolean open,
                               UnaryOperator<BlockState> decorator) {
        forEachAssembledPart(level, origin, reference, (partPos, part) ->
                level.setBlock(partPos, decorator.apply(part.setValue(AbstractGateBlock.OPEN, open)),
                        GATE_UPDATE_FLAGS));
    }

    /**
     * Visits every part of the gate {@code reference} belongs to, wherever its layout puts them.
     *
     * <p>The footprint is read once, up front, because callers like {@link #destroyGate} and
     * {@link #disassemble} remove or rewrite the origin — and with it the block entity holding the
     * number — while the walk is still going.
     */
    static void forEachAssembledPart(Level level, BlockPos origin, BlockState reference, PartConsumer action) {
        GateSize size = sizeOf(level, origin);
        Block gate = reference.getBlock();
        for (int v = 0; v < size.height(); v++) {
            for (int h = 0; h < size.width(); h++) {
                BlockPos partPos = partPos(reference, origin, h, v);
                BlockState part = level.getBlockState(partPos);
                if (!part.is(gate) || !part.getValue(AbstractGateBlock.ASSEMBLED)) {
                    continue;
                }
                action.accept(partPos, part);
            }
        }
    }

    @FunctionalInterface
    interface PartConsumer {
        void accept(BlockPos pos, BlockState state);
    }
}

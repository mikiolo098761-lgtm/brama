package brama.block;

import brama.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.function.UnaryOperator;

/**
 * A deck hinged along its bottom edge: vertical and blocking when closed, falling forward to
 * horizontal and walkable when open.
 *
 * <p>This is the only gate whose parts <em>move</em>. Every other type works because both of its
 * states occupy the same vertical footprint, so nothing has to relocate; a lowered drawbridge lies
 * outside that footprint entirely. Faking the collision is not an option — Minecraft only queries the
 * shape of blocks an entity is already next to, so an oversized {@code VoxelShape} reaching along the
 * deck is never tested and you would fall straight through it.
 *
 * <p>So rows {@code v >= 1} are physically rewritten between the vertical column above the hinge and
 * the horizontal strip in front of it. Row 0 is the hinge and never moves, which keeps the origin —
 * and with it the block entity, the owner and the access list — in one place across the whole motion.
 */
public class DrawbridgeBlock extends AbstractGateBlock {

    /**
     * Which way the deck falls: towards the positive end of the perpendicular axis, or away.
     *
     * <p>Unlike the wooden gate's swing direction this cannot live on the block entity, because it
     * decides <em>where the parts are</em>, not just how they are drawn. {@code partPos} and
     * {@code originOf} are pure blockstate maths with no level to look one up through — and finding
     * the origin would need the origin's own block entity, which is circular. It costs this type
     * double the blockstates, and that is the price of a multiblock that relocates.
     */
    public static final BooleanProperty FALL_POSITIVE = BooleanProperty.create("fall_positive");

    /** A lowered deck is a walkable slab; the hinge row uses it too so the surface is continuous. */
    private static final VoxelShape DECK = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 4.0D, 16.0D);

    private final GateWood wood;

    public DrawbridgeBlock(Properties properties, GateWood wood) {
        super(properties);
        this.wood = wood;
        this.registerDefaultState(this.defaultBlockState().setValue(FALL_POSITIVE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(FALL_POSITIVE);
    }

    public GateWood wood() {
        return this.wood;
    }

    @Override
    public String textureFamily() {
        return this.wood.deckTextures();
    }

    @Override
    public Config.GateSettings settings() {
        return Config.DRAWBRIDGE;
    }

    @Override
    protected SoundEvent openSound() {
        return SoundEvents.CHAIN_PLACE;
    }

    @Override
    protected SoundEvent closeSound() {
        return SoundEvents.CHAIN_HIT;
    }

    @Override
    public SoundEvent assembleSound() {
        return SoundEvents.CHAIN_PLACE;
    }

    /** Whether the renderer should draw pivoting support beams above the deck. */
    public boolean hasSupportArms() {
        return false;
    }

    /**
     * Tells pathfinding that a lowered deck is a bridge, so mobs will cross instead of going round.
     *
     * <p>They would not otherwise, and the reason is worth recording. A node only becomes walkable if
     * the block <em>below</em> it is solid, and a drawbridge exists precisely to span a gap — so
     * {@code WalkNodeEvaluator} looks under the deck, finds the moat, and concludes there is no floor.
     * The deck's own 4px slab never enters into it: being thinner than a full cube, it reads as empty
     * space rather than as ground.
     *
     * <p>Forge checks this hook before any of that and takes its answer as final, so declaring the
     * deck walkable settles it whatever is underneath. It is engine-level rather than per-mob, which
     * is why it works for mobs from other mods too — anything pathing on land goes through here.
     *
     * @return {@code null} for a loose block, leaving it to vanilla, which already reads a full cube
     *         correctly.
     */
    @Nullable
    @Override
    public BlockPathTypes getBlockPathType(BlockState state, BlockGetter level, BlockPos pos, @Nullable Mob mob) {
        if (!state.getValue(ASSEMBLED)) {
            return null;
        }
        // Raised, the deck is a shut door across the passage; mobs should route around it.
        return state.getValue(OPEN) ? BlockPathTypes.WALKABLE : BlockPathTypes.BLOCKED;
    }

    /** The horizontal direction the deck falls towards. */
    public static Direction fallDirection(BlockState state) {
        Direction.Axis perpendicular = GateAssembly.perpendicularAxis(state.getValue(AXIS));
        return Direction.get(state.getValue(FALL_POSITIVE)
                ? Direction.AxisDirection.POSITIVE
                : Direction.AxisDirection.NEGATIVE, perpendicular);
    }

    // ---------------------------------------------------------------- layout

    @Override
    public BlockPos partPos(BlockState reference, BlockPos origin, int h, int v) {
        BlockPos base = origin.relative(GateAssembly.widthDirection(reference.getValue(AXIS)), h);
        if (v == 0 || !reference.getValue(OPEN)) {
            return base.above(v);
        }
        return base.relative(fallDirection(reference), v);
    }

    @Override
    public BlockPos originOf(BlockState state, BlockPos pos) {
        int h = state.getValue(PART_H);
        int v = state.getValue(PART_V);
        BlockPos base = pos.relative(GateAssembly.widthDirection(state.getValue(AXIS)).getOpposite(), h);

        if (v == 0 || !state.getValue(OPEN)) {
            return base.below(v);
        }
        return base.relative(fallDirection(state).getOpposite(), v);
    }

    /** The deck falls away from whoever assembled it, matching the wooden gate's swing convention. */
    @Override
    protected UnaryOperator<BlockState> assemblyDecorator(Level level, BlockPos origin, BlockState reference,
                                                          Player owner) {
        Direction.Axis perpendicular = GateAssembly.perpendicularAxis(reference.getValue(AXIS));
        double planeCenter = (perpendicular == Direction.Axis.X ? origin.getX() : origin.getZ()) + 0.5D;
        double ownerCoord = perpendicular == Direction.Axis.X ? owner.getX() : owner.getZ();
        boolean fallPositive = ownerCoord < planeCenter;

        return part -> part.setValue(FALL_POSITIVE, fallPositive);
    }

    // ------------------------------------------------------------ relocation

    @Override
    protected boolean applyOpenState(Level level, BlockPos origin, BlockState reference, boolean open,
                                     @Nullable Player player) {
        GateSize size = GateAssembly.sizeOf(level, origin);
        // The destination layout: same gate, opposite open state.
        BlockState target = reference.setValue(OPEN, open);

        for (int v = 1; v < size.height(); v++) {
            for (int h = 0; h < size.width(); h++) {
                BlockPos to = partPos(target, origin, h, v);
                if (!level.getBlockState(to).canBeReplaced()) {
                    // Nobody to tell when the siege watch is the one raising the bridge; it simply
                    // stays down, and the obstruction is the defenders' problem to clear.
                    if (player != null) {
                        player.displayClientMessage(Component.translatable(open
                                ? "message.brama.drawbridge_no_room"
                                : "message.brama.drawbridge_obstructed"), true);
                    }
                    return false;
                }
            }
        }

        // Guarded, so clearing the source cells is not mistaken for a battering ram by onRemove.
        GateAssembly.runGuarded(() -> {
            for (int v = size.height() - 1; v >= 1; v--) {
                for (int h = 0; h < size.width(); h++) {
                    BlockPos from = partPos(reference, origin, h, v);
                    BlockState part = level.getBlockState(from);
                    if (!part.is(this)) {
                        continue;
                    }
                    level.removeBlock(from, false);
                    level.setBlock(partPos(target, origin, h, v), part.setValue(OPEN, open),
                            GateAssembly.GATE_UPDATE_FLAGS);
                }
            }

            // The hinge row stays where it is and only flips its state.
            for (int h = 0; h < size.width(); h++) {
                BlockPos hinge = partPos(target, origin, h, 0);
                BlockState part = level.getBlockState(hinge);
                if (part.is(this)) {
                    level.setBlock(hinge, part.setValue(OPEN, open), GateAssembly.GATE_UPDATE_FLAGS);
                }
            }
        });

        return true;
    }

    // ---------------------------------------------------------------- shapes

    @Override
    protected VoxelShape openOutlineShape(BlockState state, GateSize size) {
        return DECK;
    }

    @Override
    protected VoxelShape openCollisionShape(BlockState state, GateSize size) {
        return DECK;
    }
}

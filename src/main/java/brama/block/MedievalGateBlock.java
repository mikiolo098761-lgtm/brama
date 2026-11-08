package brama.block;

import brama.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.function.UnaryOperator;

/**
 * The wooden gate: opens as two leaves hinged on the outer edges, swinging away from the player.
 *
 * <p>One block per {@link GateWood}, each painted from its own texture family. Everything else about
 * them is identical, balance included — a spruce gate is no tougher than a birch one.
 */
public class MedievalGateBlock extends AbstractGateBlock {

    /** Which way the leaves swung: towards the positive end of the perpendicular axis, or away. */
    public static final BooleanProperty SWING_POSITIVE = BooleanProperty.create("swing_positive");

    /**
     * Outline shapes for an open gate. The doorway itself is left completely empty so players can
     * walk and look through it, but the two hinge columns keep a thin sliver of clickable geometry
     * so the gate can still be closed and mined afterwards.
     */
    private static final VoxelShape OPEN_HINGE_X_MIN = Block.box(0.0D, 0.0D, 0.0D, 2.0D, 16.0D, 16.0D);
    private static final VoxelShape OPEN_HINGE_X_MAX = Block.box(14.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D);
    private static final VoxelShape OPEN_HINGE_Z_MIN = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 2.0D);
    private static final VoxelShape OPEN_HINGE_Z_MAX = Block.box(0.0D, 0.0D, 14.0D, 16.0D, 16.0D, 16.0D);

    private final GateWood wood;

    public MedievalGateBlock(Properties properties, GateWood wood) {
        super(properties);
        this.wood = wood;
        this.registerDefaultState(this.defaultBlockState().setValue(SWING_POSITIVE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        // Only the swinging gate needs this, so the portcullis is not saddled with a property it
        // would never read -- which would double its blockstate count for nothing.
        builder.add(SWING_POSITIVE);
    }

    public GateWood wood() {
        return this.wood;
    }

    @Override
    public String textureFamily() {
        return this.wood.gateTextures();
    }

    @Override
    public Config.GateSettings settings() {
        return Config.WOODEN;
    }

    @Override
    protected SoundEvent openSound() {
        return SoundEvents.WOODEN_DOOR_OPEN;
    }

    @Override
    protected SoundEvent closeSound() {
        return SoundEvents.WOODEN_DOOR_CLOSE;
    }

    @Override
    public SoundEvent assembleSound() {
        return SoundEvents.WOOD_PLACE;
    }

    /**
     * The leaves swing away from whoever assembled the gate, and that is settled for good — the same
     * convention the drawbridge uses for the way its deck falls.
     */
    @Override
    protected UnaryOperator<BlockState> assemblyDecorator(Level level, BlockPos origin, BlockState reference,
                                                          Player owner) {
        Direction.Axis perpendicular = GateAssembly.perpendicularAxis(reference.getValue(AXIS));
        double planeCenter = (perpendicular == Direction.Axis.X ? origin.getX() : origin.getZ()) + 0.5D;
        double ownerCoord = perpendicular == Direction.Axis.X ? owner.getX() : owner.getZ();
        boolean swingPositive = ownerCoord < planeCenter;

        return part -> part.setValue(SWING_POSITIVE, swingPositive);
    }

    @Override
    protected UnaryOperator<BlockState> openDecorator(Level level, BlockPos origin, BlockState clicked,
                                                      @Nullable Player player, boolean open) {
        // A real gate has one hinge side. It used to swing away from whoever opened it, which meant
        // the same gate opened towards you or away depending on where you stood; now the direction is
        // whatever assembly fixed it to, and opening only carries it through unchanged. Which is also
        // why nothing here needs the player, and so the siege watch can shut a gate with nobody
        // standing at it.
        boolean fixed = clicked.getValue(SWING_POSITIVE);
        return part -> part.setValue(SWING_POSITIVE, fixed);
    }

    @Override
    protected VoxelShape openOutlineShape(BlockState state, GateSize size) {
        int h = state.getValue(PART_H);
        boolean alongX = state.getValue(AXIS) == Direction.Axis.X;

        if (h == 0) {
            return alongX ? OPEN_HINGE_X_MIN : OPEN_HINGE_Z_MIN;
        }
        if (h == size.width() - 1) {
            return alongX ? OPEN_HINGE_X_MAX : OPEN_HINGE_Z_MAX;
        }
        return Shapes.empty();
    }

    @Override
    protected VoxelShape openCollisionShape(BlockState state, GateSize size) {
        return Shapes.empty();
    }
}

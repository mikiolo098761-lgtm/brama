package brama.block;

import brama.Config;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The portcullis: opens by winching straight up rather than swinging.
 *
 * <p>It rises by {@link GateSize#liftHeight()}, clearing the bottom rows of the frame while the rest
 * of the bars stay in place. Nothing physically moves — the blocks are where they always were, and
 * only the rendered bars and the collision shift — which is exactly why the portcullis can slide up
 * through solid blocks without being obstructed. No headroom check is performed on purpose.
 *
 * <p>The one gate with no wood variants: a grate is iron, so there is nothing for a wood to change.
 */
public class PortcullisBlock extends AbstractGateBlock {

    public PortcullisBlock(Properties properties) {
        super(properties);
    }

    @Override
    public String textureFamily() {
        return "portcullis";
    }

    @Override
    public Config.GateSettings settings() {
        return Config.PORTCULLIS;
    }

    @Override
    protected SoundEvent openSound() {
        return SoundEvents.IRON_DOOR_OPEN;
    }

    @Override
    protected SoundEvent closeSound() {
        return SoundEvents.IRON_DOOR_CLOSE;
    }

    @Override
    public SoundEvent assembleSound() {
        return SoundEvents.CHAIN_PLACE;
    }

    /**
     * Rows below the lift are the doorway and become completely empty; rows at or above it still
     * hold bars. Rows that rose past the top of the frame sit in blocks the gate does not own, so
     * they get no collision.
     */
    @Override
    protected VoxelShape openOutlineShape(BlockState state, GateSize size) {
        return state.getValue(PART_V) < size.liftHeight()
                ? Shapes.empty()
                : closedPanel(state);
    }

    @Override
    protected VoxelShape openCollisionShape(BlockState state, GateSize size) {
        return openOutlineShape(state, size);
    }
}

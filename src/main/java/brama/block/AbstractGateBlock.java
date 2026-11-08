package brama.block;

import brama.Config;
import brama.blockentity.GateBlockEntity;
import brama.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.function.UnaryOperator;

/**
 * Everything every gate type shares: the multiblock blockstate, hammer-driven assembly, ownership,
 * damage stages and siege handling. See {@link GateAssembly} for the multiblock arithmetic.
 *
 * <p>Loose (unassembled) blocks are plain solid cubes and do nothing on their own. Hitting a complete
 * frame of them with {@code brama:gate_hammer} links them: they become {@link RenderShape#INVISIBLE}
 * and the whole gate is drawn by the BlockEntityRenderer on the origin part instead.
 *
 * <p>Subclasses supply only what actually differs between gate types — how the gate looks when open,
 * its sounds, which config section balances it, and which texture family paints it.
 */
public abstract class AbstractGateBlock extends Block implements EntityBlock {

    /** The axis the gate plane spans. Gates are symmetric, so there is no "backwards" placement. */
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;
    /** Column within the gate, ascending along the positive direction of {@link #AXIS}. */
    public static final IntegerProperty PART_H = IntegerProperty.create("part_h", 0, GateSize.MAX_WIDTH - 1);
    /** Row within the gate, 0 being the bottom. */
    public static final IntegerProperty PART_V = IntegerProperty.create("part_v", 0, GateSize.MAX_HEIGHT - 1);
    /** False while the block is a loose cube, true once it is part of a complete gate. */
    public static final BooleanProperty ASSEMBLED = BooleanProperty.create("assembled");
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;
    /**
     * Wear level. Belongs to the gate as a whole rather than the individual block, so every part of
     * an assembled gate always carries the same value.
     */
    public static final IntegerProperty DAMAGE = IntegerProperty.create("damage", 0, GateSize.MAX_DAMAGE_STAGES - 1);

    /**
     * The hardness every gate type is registered with. Configured hardness is applied by scaling
     * against this, since {@code Block.Properties#strength} is fixed at construction.
     */
    public static final float DECLARED_HARDNESS = 30.0F;

    /**
     * Blast resistance baked into the block at registration, for explosions that never ask
     * {@link #getExplosionResistance}.
     *
     * <p>Zero on purpose. This is the figure a siege mod sees when it brings its own
     * {@code ExplosionDamageCalculator} or walks blocks itself, and a gate it reads as obsidian — as
     * this used to be, at 1200 — is a gate it silently skips.
     *
     * <p>Even a modest figure is too much. Entering a block costs an explosion
     * {@code (resistance + 0.3) * 0.3}, and artillery mods fire far smaller charges than TNT: the
     * cannons this was tested against use 1.33 and 2.0 against TNT's 4.0. At stone's 6 the toll is
     * 1.89, more than such a shell carries, so it would detonate against the gate and achieve
     * nothing.
     *
     * <p>Nothing is lost by zeroing it. Immunity is decided in {@link #onBlockExploded}, and how much
     * punishment a gate can take is set by its damage stages, not by this.
     */
    public static final float DECLARED_BLAST_RESISTANCE = 0.0F;

    /**
     * A closed gate is a thin panel rather than a full cube, matching what the renderer draws. It is
     * also the clearest signal that assembly worked: loose cubes visibly become one slab.
     */
    protected static final VoxelShape CLOSED_PANEL_X = Block.box(0.0D, 0.0D, 6.0D, 16.0D, 16.0D, 10.0D);
    protected static final VoxelShape CLOSED_PANEL_Z = Block.box(6.0D, 0.0D, 0.0D, 10.0D, 16.0D, 16.0D);

    protected AbstractGateBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(AXIS, Direction.Axis.X)
                .setValue(PART_H, 0)
                .setValue(PART_V, 0)
                .setValue(ASSEMBLED, false)
                .setValue(OPEN, false)
                .setValue(DAMAGE, 0));
    }

    // ------------------------------------------------------------ gate type

    /** The config section balancing this gate type. */
    public abstract Config.GateSettings settings();

    /**
     * The {@code textures/gate/} family this gate is painted with, e.g. {@code wooden} or
     * {@code spruce_deck}. A plain string rather than a {@code GateTextures.Set}, so that common
     * code never names a client-only class.
     */
    public abstract String textureFamily();

    protected abstract SoundEvent openSound();

    protected abstract SoundEvent closeSound();

    /** Played once when the hammer links a frame into a gate. */
    public abstract SoundEvent assembleSound();

    /** Clickable geometry while open. Must leave the passage clear but stay reachable somewhere. */
    protected abstract VoxelShape openOutlineShape(BlockState state, GateSize size);

    protected abstract VoxelShape openCollisionShape(BlockState state, GateSize size);

    /**
     * Extra per-part state to stamp on when the gate is opened or closed — the swinging gate uses it
     * to record which way the leaves went. Applied to every part.
     */
    protected UnaryOperator<BlockState> openDecorator(Level level, BlockPos origin, BlockState clicked,
                                                      @Nullable Player player, boolean open) {
        return UnaryOperator.identity();
    }

    /**
     * Extra per-part state stamped on when a gate is first assembled — the drawbridge uses it to fix
     * which way its deck will fall. Only consulted when a player did the assembling.
     */
    protected UnaryOperator<BlockState> assemblyDecorator(Level level, BlockPos origin, BlockState reference,
                                                          Player owner) {
        return UnaryOperator.identity();
    }

    /**
     * Where part {@code (h, v)} of a gate described by {@code reference} sits in the world.
     *
     * <p>Defaults to the vertical plane every gate is built in. The drawbridge overrides it, because
     * its deck lies flat in front of the hinge once lowered — the only gate whose parts move.
     */
    public BlockPos partPos(BlockState reference, BlockPos origin, int h, int v) {
        return origin.above(v).relative(GateAssembly.widthDirection(reference.getValue(AXIS)), h);
    }

    /** Inverse of {@link #partPos}: walks back from any part to its gate's origin. */
    public BlockPos originOf(BlockState state, BlockPos pos) {
        Direction width = GateAssembly.widthDirection(state.getValue(AXIS));
        return pos.below(state.getValue(PART_V)).relative(width.getOpposite(), state.getValue(PART_H));
    }

    /**
     * Applies the new open state to every part.
     *
     * @param player whoever worked the gate, or null when nobody did — the siege watch shutting it on
     *               its own, a command block, another mod. Subclasses must not assume a player.
     * @return false if the gate could not move, in which case the subclass has already said why to
     *         the player, if there was one, and no sound is played. Only the drawbridge can fail,
     *         when its deck has nowhere to land.
     */
    protected boolean applyOpenState(Level level, BlockPos origin, BlockState reference, boolean open,
                                     @Nullable Player player) {
        GateAssembly.setOpen(level, origin, reference, open,
                openDecorator(level, origin, reference, player, open));
        return true;
    }

    /**
     * Opens or shuts the gate {@code reference} belongs to, with the sound and game event that go
     * with it.
     *
     * <p>The one way in for anything that is not a player's own right-click — the siege watch in
     * particular, which is why the whole open path takes a nullable player.
     *
     * @return false if the gate was already in that state, or could not move.
     */
    public boolean setOpen(Level level, BlockPos origin, BlockState reference, boolean open,
                           @Nullable Player player) {
        if (level.isClientSide || reference.getValue(OPEN) == open) {
            return false;
        }
        if (!applyOpenState(level, origin, reference, open, player)) {
            return false;
        }

        level.playSound(null, origin, open ? openSound() : closeSound(), SoundSource.BLOCKS, 1.0F, 0.7F);
        level.gameEvent(player, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, origin);
        return true;
    }

    /** Highest damage value this gate type reaches before collapsing. */
    public int maxDamage() {
        return settings().maxDamage();
    }

    /** Whether this gate is one hit from collapsing. Treats over-max gates as critical. */
    public boolean isCritical(BlockState state) {
        return state.getValue(DAMAGE) >= maxDamage();
    }

    // ------------------------------------------------------------ blockstate

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS, PART_H, PART_V, ASSEMBLED, OPEN, DAMAGE);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos against = context.getClickedPos().relative(context.getClickedFace().getOpposite());
        BlockState againstState = context.getLevel().getBlockState(against);

        // Building a gate by hand is much less fiddly if blocks placed against an existing gate
        // block inherit its axis instead of following whichever way the player happens to face.
        Direction.Axis axis = againstState.is(this)
                ? againstState.getValue(AXIS)
                : context.getHorizontalDirection().getClockWise().getAxis();

        return this.defaultBlockState().setValue(AXIS, axis);
    }

    // ------------------------------------------------------------- footprint

    /**
     * The footprint of the gate this part belongs to.
     *
     * <p>Kept on the origin's block entity rather than in the blockstate, and the reason is wood.
     * Eleven footprints as a blockstate property multiply every gate's state count by eleven, and
     * eleven wood variants multiply the number of gate blocks by the same — together they came to
     * nearly two million blockstates, for each of which Minecraft builds a shape cache at startup.
     * Only the origin ever needs the number and it already has a block entity to keep it in, so that
     * is where it went. {@link #hasDynamicShape()} is the other half of the arrangement.
     *
     * @return the stored footprint, or {@link GateSize#SIZE_3X3} when there is no block entity to ask
     *         — a chunk mid-load, or {@code EmptyBlockGetter} during registry setup. Only the shape
     *         of an <em>open</em> gate depends on this, so a wrong guess costs a sliver of outline in
     *         the wrong column and nothing else.
     */
    public static GateSize sizeAt(BlockGetter level, BlockPos pos, BlockState state) {
        if (!state.getValue(ASSEMBLED)) {
            return GateSize.SIZE_3X3;
        }
        BlockPos origin = GateAssembly.originOf(state, pos);
        return level.getBlockEntity(origin) instanceof GateBlockEntity gate ? gate.size() : GateSize.SIZE_3X3;
    }

    /**
     * True, because {@link #sizeAt} reads the footprint from a block entity, which makes the shape of
     * a state depend on where that state stands.
     *
     * <p>Vanilla would otherwise compute one shape per state at registry freeze, using
     * {@code EmptyBlockGetter} and therefore no block entity, and hand that same answer back for
     * every gate in the world. Declaring the shape dynamic also stops it building those caches at
     * all, which is a saving rather than a cost: there is nothing to lose in a cache whose value
     * would be wrong.
     */
    @Override
    public boolean hasDynamicShape() {
        return true;
    }

    // ---------------------------------------------------------- interaction

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!state.getValue(ASSEMBLED)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        BlockPos origin = GateAssembly.originOf(state, pos);

        if (level.getBlockEntity(origin) instanceof GateBlockEntity gate && !gate.canOpen(player)) {
            player.displayClientMessage(Component.translatable("message.brama.gate_locked", gate.ownerName()), true);
            return InteractionResult.CONSUME;
        }

        setOpen(level, origin, state, !state.getValue(OPEN), player);
        return InteractionResult.CONSUME;
    }

    // ------------------------------------------------------------ destruction

    /**
     * Breaking an assembled gate wears it down a stage instead of removing it, until the last stage
     * brings the whole thing down at once.
     *
     * <p>This is the Forge hook that actually performs removal, so returning false leaves the block
     * standing <em>and</em> suppresses vanilla's loot drop — exactly what a damage tick needs.
     */
    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player,
                                       boolean willHarvest, FluidState fluid) {
        // Damage stages belong to the gate, not to building material. A loose block breaks the
        // ordinary way: one hit, one drop.
        if (!state.getValue(ASSEMBLED)) {
            return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
        }

        if (!isCritical(state)) {
            if (!level.isClientSide) {
                GateAssembly.advanceDamage(level, pos, state);
                level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(state));
                player.displayClientMessage(Component.translatable(state.getValue(DAMAGE) + 1 >= maxDamage()
                        ? "message.brama.gate_critical"
                        : "message.brama.gate_damaged"), true);
            }
            return false;
        }

        this.playerWillDestroy(level, pos, state, player);

        if (level.isClientSide) {
            // Client-side prediction only removes the struck block; the server clears the rest.
            return level.setBlock(pos, fluid.createLegacyBlock(), 11);
        }

        // The whole teardown, struck block included, runs inside GateAssembly's update guard. That is
        // what makes any *other* removal of an assembled part identifiable as an external force.
        // willHarvest is not usable for the drop test: Minecraft hardcodes true from the server game
        // mode and false from the client one, so it signals side rather than harvestability.
        boolean dropItems = !player.isCreative() && player.hasCorrectToolForDrops(state);
        GateAssembly.destroyGate(level, pos, state, dropItems, false);

        // Vanilla still drops the struck block itself: ServerPlayerGameMode captured its state and
        // block entity before this call and hands them to playerDestroy afterwards.
        return true;
    }

    /**
     * Anything that removes a gate block without going through the player-breaking path — a
     * battering ram, WorldEdit, another mod — lands here, and is treated as a siege hit.
     */
    @Override
    public void onBlockExploded(BlockState state, Level level, BlockPos pos, Explosion explosion) {
        if (level.isClientSide || !state.getValue(ASSEMBLED)) {
            super.onBlockExploded(state, level, pos, explosion);
            return;
        }

        // The one place immunity is decided. It used to be expressed purely as a large resistance
        // value, which meant it only held for explosions that consult getExplosionResistance below --
        // vanilla ones. Deciding it here instead covers every explosion that can reach this method,
        // whoever built it, and is what lets the declared resistance stay low enough for siege mods
        // to notice the gate in the first place.
        if (immuneTo(explosion)) {
            return;
        }

        if (isCritical(state)) {
            GateAssembly.destroyGate(level, pos, state, false, false);
        } else {
            // Debounced, so one blast costs one stage no matter how many parts it caught.
            GateAssembly.advanceDamageOnce(level, pos, state);
        }
    }

    @Override
    public float getExplosionResistance(BlockState state, BlockGetter level, BlockPos pos, Explosion explosion) {
        // Only vanilla-path explosions ask. Kept high for the immune case so a creeper blast is
        // absorbed rather than carrying on through the gateway into whatever is behind it -- the
        // gate's survival itself is guaranteed by onBlockExploded, not by this number.
        return (float) (immuneTo(explosion)
                ? settings().explosionResistance()
                : settings().siegeExplosionResistance());
    }

    /** Whether this blast should leave the gate standing and undamaged. */
    private static boolean immuneTo(Explosion explosion) {
        return !Config.explosionsDamageGates()
                || (Config.creeperProof() && explosion.getDirectSourceEntity() instanceof Creeper);
    }

    @Override
    public float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        float base = super.getDestroyProgress(state, player, level, pos);

        // The fortification is the assembled gate; a loose block is just building material.
        if (!state.getValue(ASSEMBLED)) {
            return base * (float) (DECLARED_HARDNESS / settings().looseBlockHardness());
        }

        // Each stage of wear halves what is left of the gate's toughness.
        return base * (float) (DECLARED_HARDNESS / settings().gateHardness()) * (1 << state.getValue(DAMAGE));
    }

    /**
     * Keeps a gate from being left as an invisible wall. An assembled part is only drawn by the
     * renderer on its origin, so a remnant whose origin has gone is solid but invisible. If that is
     * ever detected, fall back to loose blocks — visible, breakable and recoverable.
     */
    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean isMoving) {
        super.neighborChanged(state, level, pos, block, fromPos, isMoving);

        if (level.isClientSide || GateAssembly.isUpdating() || !state.getValue(ASSEMBLED)) {
            return;
        }
        if (GateAssembly.hasValidOrigin(level, pos, state)) {
            return;
        }

        GateAssembly.disassemble(level, GateAssembly.originOf(state, pos), state, null);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        // Guarded on the new state still being this gate block, otherwise the setBlock calls inside
        // disassemble() would recurse back into here for each part. Every removal this mod performs
        // itself runs inside GateAssembly's update guard, so an unguarded removal of an assembled
        // part can only have come from outside — a battering ram, WorldEdit, another mod — and is
        // routed to the damage system rather than quietly disassembling the gate into loose rubble.
        if (!GateAssembly.isUpdating() && state.getValue(ASSEMBLED) && !newState.is(this)) {
            GateAssembly.handleExternalBreak(level, pos, state);
        }

        // BlockBehaviour#onRemove only drops the BlockEntity when the *block* changes, but here the
        // origin can stop being an origin while staying the same block, so drop it explicitly.
        //
        // This tests the *live* state rather than the newState the caller intended, because
        // handleExternalBreak above may already have put the origin back. That restore reuses the
        // still-attached BlockEntity, and trusting newState here would delete it again -- silently
        // wiping the gate's owner and access list on every battering-ram hit that lands on the origin.
        BlockState current = level.getBlockState(pos);
        if (GateAssembly.isOrigin(state) && !(current.is(this) && GateAssembly.isOrigin(current))) {
            level.removeBlockEntity(pos);
        }

        super.onRemove(state, level, pos, newState, isMoving);
    }

    // ---------------------------------------------------------------- shapes

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return state.getValue(ASSEMBLED) ? RenderShape.INVISIBLE : RenderShape.MODEL;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (!state.getValue(ASSEMBLED)) {
            return Shapes.block();
        }
        return state.getValue(OPEN)
                ? openOutlineShape(state, sizeAt(level, pos, state))
                : closedPanel(state);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (!state.getValue(ASSEMBLED)) {
            return Shapes.block();
        }
        return state.getValue(OPEN)
                ? openCollisionShape(state, sizeAt(level, pos, state))
                : closedPanel(state);
    }

    protected static VoxelShape closedPanel(BlockState state) {
        return state.getValue(AXIS) == Direction.Axis.X ? CLOSED_PANEL_X : CLOSED_PANEL_Z;
    }

    // --------------------------------------------------------- block entity

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        // Only the origin gets a BlockEntity; returning null for the other parts is handled
        // correctly by LevelChunk.
        return GateAssembly.isOrigin(state) ? new GateBlockEntity(pos, state) : null;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        // The open animation is pure presentation, so it is only ticked on the client.
        if (!level.isClientSide || !GateAssembly.isOrigin(state) || type != ModBlockEntities.GATE.get()) {
            return null;
        }
        return (tickLevel, tickPos, tickState, blockEntity) ->
                GateBlockEntity.clientTick(tickState, (GateBlockEntity) blockEntity);
    }
}

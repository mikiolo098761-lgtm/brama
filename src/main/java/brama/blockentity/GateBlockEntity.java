package brama.blockentity;

import brama.Config;
import brama.block.AbstractGateBlock;
import brama.block.GateSize;
import brama.claim.ClaimCompat;
import brama.registry.ModBlockEntities;
import brama.siege.GateWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.scores.Team;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Lives only on the origin part of an assembled gate, of any type. Holds the gate's footprint, the
 * open animation and the gate's access list.
 *
 * <p>The authoritative open/closed state is in the blockstate, which already syncs to clients, so the
 * animation needs no packets — it just chases the value implied by the blockstate. The footprint is
 * synced because the renderer and the collision shapes both need it on the client. The access list is
 * saved to disk but deliberately <em>not</em> synced: every permission check runs on the server, so
 * the client copy stays empty and weightless.
 */
public class GateBlockEntity extends BlockEntity {

    private static final String TAG_SIZE = "Size";
    private static final String TAG_OWNER = "Owner";
    private static final String TAG_OWNER_NAME = "OwnerName";
    private static final String TAG_OPS = "Ops";
    private static final String TAG_TRUSTED = "Trusted";
    private static final String TAG_TEAMS = "TrustedTeams";

    /**
     * How far through opening the gate is, 0 shut to 1 fully open. Each gate type turns this into
     * its own motion — a swing angle for the wooden gate, a lift offset for the portcullis.
     */
    private float progress;
    private float previousProgress;

    /**
     * The gate's footprint. Used to live in the blockstate; see
     * {@link AbstractGateBlock#sizeAt} for why it moved here.
     */
    private GateSize size = GateSize.SIZE_3X3;

    /**
     * False for a gate saved before the footprint moved onto the block entity, which is the signal
     * to measure it from the parts standing around it. Never written to disk: a gate that has been
     * measured once saves its size like any other.
     */
    private boolean sizeKnown = true;

    @Nullable
    private UUID owner;
    private String ownerName = "";
    private final Set<UUID> ops = new HashSet<>();
    private final Set<UUID> trusted = new HashSet<>();
    /** Scoreboard team *names*, so trust survives a team being deleted and recreated. */
    private final Set<String> trustedTeams = new HashSet<>();

    public GateBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.GATE.get(), pos, state);

        // Start already at the target so gates loaded from disk (or coming back into render
        // distance) do not play a phantom opening.
        float target = target(state);
        this.progress = target;
        this.previousProgress = target;
    }

    public static void clientTick(BlockState state, GateBlockEntity blockEntity) {
        blockEntity.previousProgress = blockEntity.progress;

        float target = target(state);
        float step = 1.0F / settings(state).openTicks();
        if (blockEntity.progress < target) {
            blockEntity.progress = Math.min(target, blockEntity.progress + step);
        } else if (blockEntity.progress > target) {
            blockEntity.progress = Math.max(target, blockEntity.progress - step);
        }
    }

    private static float target(BlockState state) {
        return state.hasProperty(AbstractGateBlock.OPEN) && state.getValue(AbstractGateBlock.OPEN) ? 1.0F : 0.0F;
    }

    private static Config.GateSettings settings(BlockState state) {
        return state.getBlock() instanceof AbstractGateBlock gate ? gate.settings() : Config.WOODEN;
    }

    /** 0 shut to 1 fully open, interpolated for smooth rendering between ticks. */
    public float openProgress(float partialTick) {
        return Mth.lerp(partialTick, this.previousProgress, this.progress);
    }

    // ---------------------------------------------------------------- footprint

    public GateSize size() {
        return this.size;
    }

    /** Set once, by assembly, and synced because the client draws the gate from it. */
    public void setSize(GateSize size) {
        this.size = size;
        this.sizeKnown = true;
        setChanged();
        if (this.level != null && !this.level.isClientSide) {
            // Nothing here changes the blockstate, so the chunk would otherwise never resend it.
            this.level.sendBlockUpdated(this.worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /** Whether this gate still needs its footprint measured from the parts around it. */
    public boolean needsSizeMeasured() {
        return !this.sizeKnown;
    }

    // ------------------------------------------------------------------ access

    public boolean isUnowned() {
        return this.owner == null;
    }

    /** Display name of the owner, or an empty string when the gate is unowned. */
    public String ownerName() {
        return this.ownerName;
    }

    @Nullable
    public UUID owner() {
        return this.owner;
    }

    public Set<UUID> ops() {
        return Collections.unmodifiableSet(this.ops);
    }

    public Set<UUID> trusted() {
        return Collections.unmodifiableSet(this.trusted);
    }

    public Set<String> trustedTeams() {
        return Collections.unmodifiableSet(this.trustedTeams);
    }

    /** May this player open the gate? Unowned gates stay public so pre-ownership gates keep working. */
    public boolean canOpen(Player player) {
        if (canManage(player)) {
            return true;
        }
        if (!Config.ownershipEnabled()) {
            return true;
        }
        if (this.trusted.contains(player.getUUID())) {
            return true;
        }
        Team team = player.getTeam();
        if (team != null && this.trustedTeams.contains(team.getName())) {
            return true;
        }
        // Whoever the claim mod calls a teammate or an ally of the owner. This is the inward half of
        // the claim integration -- the outward half being the siege watch, which shuts gates on
        // whoever it calls an enemy.
        return Config.trustClaimAllies() && ClaimCompat.areAllies(this.level, this.owner, player);
    }

    /** May this player edit the access list, or take the gate apart with the hammer? */
    public boolean canManage(Player player) {
        return canPromote(player) || this.ops.contains(player.getUUID());
    }

    /** May this player appoint or remove co-owners? Owner and server admins only. */
    public boolean canPromote(Player player) {
        if (!Config.ownershipEnabled()) {
            return true;
        }
        if (this.owner == null) {
            return Config.unownedGatesPublic() || adminBypass(player);
        }
        return this.owner.equals(player.getUUID()) || adminBypass(player);
    }

    /**
     * Whether this player overrides every gate's access list.
     *
     * <p>Operator level alone used to be enough, which made a moderator playing the server's own
     * survival world a skeleton key: every locked gate on the map opened to them, and nothing told
     * the owner. The permission level is still required, but it now has to be exercised in creative
     * mode — the mode you are in when you are administrating rather than playing.
     *
     * <p>It covers the {@code /gate} commands as well as opening, and has to. A moderator who could
     * still run {@code /gate op} in survival would simply grant themselves the gate and open it.
     */
    public static boolean adminBypass(Player player) {
        return hasAdminLevel(player) && (!Config.adminBypassRequiresCreative() || player.isCreative());
    }

    /** The permission half of {@link #adminBypass}, for telling an admin why nothing happened. */
    public static boolean hasAdminLevel(Player player) {
        return player.hasPermissions(Config.adminPermissionLevel());
    }

    public void setOwner(Player player) {
        this.owner = player.getUUID();
        this.ownerName = player.getGameProfile().getName();
        setChanged();
    }

    public boolean setTrusted(UUID id, boolean add) {
        boolean changed = add ? this.trusted.add(id) : this.trusted.remove(id);
        if (changed) {
            setChanged();
        }
        return changed;
    }

    public boolean setOp(UUID id, boolean add) {
        boolean changed = add ? this.ops.add(id) : this.ops.remove(id);
        if (changed) {
            setChanged();
        }
        return changed;
    }

    public boolean setTrustedTeam(String team, boolean add) {
        boolean changed = add ? this.trustedTeams.add(team) : this.trustedTeams.remove(team);
        if (changed) {
            setChanged();
        }
        return changed;
    }

    // -------------------------------------------------------------- lifecycle

    /**
     * Joins the server-side register of live gates, which is what lets the siege watch find the gates
     * near a player without searching the world for them.
     */
    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level instanceof ServerLevel serverLevel) {
            GateWatch.gateLoaded(serverLevel, this);
        }
    }

    @Override
    public void setRemoved() {
        if (this.level instanceof ServerLevel serverLevel) {
            GateWatch.gateUnloaded(serverLevel, this.worldPosition);
        }
        super.setRemoved();
    }

    // ------------------------------------------------------------ persistence

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString(TAG_SIZE, this.size.getSerializedName());
        if (this.owner != null) {
            tag.putUUID(TAG_OWNER, this.owner);
            tag.putString(TAG_OWNER_NAME, this.ownerName);
        }
        tag.put(TAG_OPS, writeIds(this.ops));
        tag.put(TAG_TRUSTED, writeIds(this.trusted));

        ListTag teams = new ListTag();
        this.trustedTeams.forEach(team -> teams.add(StringTag.valueOf(team)));
        tag.put(TAG_TEAMS, teams);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);

        GateSize stored = tag.contains(TAG_SIZE) ? sizeByName(tag.getString(TAG_SIZE)) : null;
        this.size = stored == null ? GateSize.SIZE_3X3 : stored;
        // Absent, or naming a footprint this version no longer has: either way the stored value
        // cannot be trusted and the gate is measured instead.
        this.sizeKnown = stored != null;

        this.owner = tag.hasUUID(TAG_OWNER) ? tag.getUUID(TAG_OWNER) : null;
        this.ownerName = tag.getString(TAG_OWNER_NAME);
        readIds(tag, TAG_OPS, this.ops);
        readIds(tag, TAG_TRUSTED, this.trusted);

        this.trustedTeams.clear();
        ListTag teams = tag.getList(TAG_TEAMS, Tag.TAG_STRING);
        for (int i = 0; i < teams.size(); i++) {
            this.trustedTeams.add(teams.getString(i));
        }
    }

    @Nullable
    private static GateSize sizeByName(String name) {
        for (GateSize size : GateSize.values()) {
            if (size.getSerializedName().equals(name.toLowerCase(Locale.ROOT))) {
                return size;
            }
        }
        return null;
    }

    /** The footprint has to reach the client: the renderer and the open gate's shapes both read it. */
    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_SIZE, this.size.getSerializedName());
        return tag;
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private static ListTag writeIds(Set<UUID> ids) {
        ListTag list = new ListTag();
        ids.forEach(id -> list.add(NbtUtils.createUUID(id)));
        return list;
    }

    private static void readIds(CompoundTag tag, String key, Set<UUID> into) {
        into.clear();
        ListTag list = tag.getList(key, Tag.TAG_INT_ARRAY);
        for (int i = 0; i < list.size(); i++) {
            into.add(NbtUtils.loadUUID(list.get(i)));
        }
    }

    @Override
    public AABB getRenderBoundingBox() {
        // The renderer draws the whole gate from this one position, and both types put geometry
        // outside the footprint: swung leaves reach sideways, a raised portcullis reaches above.
        int reach = Math.max(this.size.width(), this.size.height()) + this.size.liftHeight();
        return new AABB(this.worldPosition).inflate(reach);
    }
}

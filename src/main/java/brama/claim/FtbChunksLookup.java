package brama.claim;

import brama.Brama;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * FTB Chunks for the land, FTB Teams for the diplomacy.
 *
 * <p>The pair is the reason this provider is worth having over the generic probe: FTB Teams already
 * models what a besieging army needs modelled — parties with members and declared allies — so a gate
 * can tell a neighbour's banner from an enemy's without anybody typing a name.
 *
 * <p>Bound by reflection; see {@link Reflect} for why, and for what happens when a version does not
 * fit.
 */
final class FtbChunksLookup implements ClaimLookup {

    static final String ID = "ftbchunks";

    private boolean bound;
    private boolean retired;

    /** FTB Chunks: the claim at a chunk, and the team holding it. */
    private Object chunkManager;
    private Class<?> chunkDimPos;
    private Method getChunk;
    private Method getTeamData;
    private Method getTeamId;

    /** FTB Teams: the team a player belongs to, and who it counts as its own. */
    private Object teamManager;
    private Method teamForPlayer;
    private Method teamId;
    private Method isMember;
    private Method isAlly;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean available() {
        if (this.retired) {
            return false;
        }
        if (!this.bound) {
            bind();
        }
        return !this.retired;
    }

    private void bind() {
        this.bound = true;
        try {
            Object chunksApi = Reflect.call(
                    Reflect.method(Reflect.type("dev.ftb.mods.ftbchunks.api.FTBChunksAPI"), 0, "api", "getInstance"),
                    null);
            this.chunkManager = Reflect.call(Reflect.method(classOf(chunksApi), 0, "getManager"), chunksApi);
            this.chunkDimPos = Reflect.type("dev.ftb.mods.ftbchunks.api.ChunkDimPos");
            this.getChunk = Reflect.method(classOf(this.chunkManager), 1, "getChunk");

            Object teamsApi = Reflect.call(
                    Reflect.method(Reflect.type("dev.ftb.mods.ftbteams.api.FTBTeamsAPI"), 0, "api", "getInstance"),
                    null);
            this.teamManager = Reflect.call(Reflect.method(classOf(teamsApi), 0, "getManager"), teamsApi);
            this.teamForPlayer = Reflect.method(classOf(this.teamManager), 1,
                    "getTeamForPlayerID", "getPlayerTeamForPlayerID", "getTeamForPlayer", "getTeamByID");

            if (this.chunkManager == null || this.chunkDimPos == null || this.getChunk == null
                    || this.teamManager == null || this.teamForPlayer == null) {
                retire("FTB Chunks or FTB Teams is installed but its API does not look the way this "
                        + "version of Brama expects");
                return;
            }
            Brama.LOGGER.info("brama: siege watch is reading claims from FTB Chunks and teams from FTB Teams");
        } catch (Throwable thrown) {
            Brama.LOGGER.debug("brama: FTB claim lookup failed to bind", thrown);
            retire("FTB Chunks could not be reached");
        }
    }

    private void retire(String why) {
        this.retired = true;
        Brama.LOGGER.warn("brama: {} - falling back to the generic claim probe. "
                + "Set siege.claimProvider explicitly to silence this.", why);
    }

    @Override
    public Verdict intruderAt(ServerLevel level, BlockPos pos, ServerPlayer player, UUID owner) {
        if (owner == null || !available()) {
            return Verdict.UNKNOWN;
        }

        UUID claimTeam = teamAt(level, pos);
        if (claimTeam == null) {
            return Verdict.UNKNOWN;
        }

        Object ownerTeam = teamOf(owner);
        UUID ownerTeamId = idOf(ownerTeam);
        // Somebody else's claim. A gate standing in it is not this mod's quarrel to pick.
        if (ownerTeamId == null || !ownerTeamId.equals(claimTeam)) {
            return Verdict.UNKNOWN;
        }

        return welcome(ownerTeam, player.getUUID()) ? Verdict.WELCOME : Verdict.INTRUDER;
    }

    @Override
    public boolean areAllies(ServerLevel level, UUID owner, ServerPlayer player) {
        if (owner == null || !available()) {
            return false;
        }
        return welcome(teamOf(owner), player.getUUID());
    }

    /** The team holding the chunk {@code pos} falls in, or null for unclaimed land. */
    private UUID teamAt(ServerLevel level, BlockPos pos) {
        int chunkX = SectionPos.blockToSectionCoord(pos.getX());
        int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());

        // The same key type has been spelled several ways across versions; offer each shape.
        Object key = Reflect.instantiate(this.chunkDimPos, level.dimension(), chunkX, chunkZ);
        if (key == null) {
            key = Reflect.instantiate(this.chunkDimPos, level, pos);
        }
        if (key == null) {
            key = Reflect.instantiate(this.chunkDimPos, level.dimension().location(), chunkX, chunkZ);
        }
        if (key == null) {
            retire("FTB Chunks' chunk key could not be built");
            return null;
        }

        Object chunk = Reflect.unwrap(Reflect.call(this.getChunk, this.chunkManager, key));
        if (chunk == null) {
            return null;
        }

        if (this.getTeamData == null) {
            this.getTeamData = Reflect.method(chunk.getClass(), 0, "getTeamData", "getTeam");
        }
        Object teamData = Reflect.unwrap(Reflect.call(this.getTeamData, chunk));
        if (teamData == null) {
            return null;
        }

        if (this.getTeamId == null) {
            this.getTeamId = Reflect.method(teamData.getClass(), 0, "getTeamId", "getId", "getUuid");
        }
        return Reflect.call(this.getTeamId, teamData) instanceof UUID id ? id : null;
    }

    private Object teamOf(UUID player) {
        return Reflect.unwrap(Reflect.call(this.teamForPlayer, this.teamManager, player));
    }

    private UUID idOf(Object team) {
        if (team == null) {
            return null;
        }
        if (this.teamId == null) {
            this.teamId = Reflect.method(team.getClass(), 0, "getId", "getTeamId", "getUuid");
        }
        return Reflect.call(this.teamId, team) instanceof UUID id ? id : null;
    }

    /** Member of the team, or allied to it. An ally check this cannot perform counts as no ally. */
    private boolean welcome(Object team, UUID player) {
        if (team == null) {
            return false;
        }
        if (this.isMember == null) {
            this.isMember = Reflect.method(team.getClass(), 1, "isMember", "hasMember", "isPlayerIn");
        }
        if (Reflect.flag(this.isMember, team, player)) {
            return true;
        }
        if (this.isAlly == null) {
            this.isAlly = Reflect.method(team.getClass(), 1, "isAlly", "isAlliedWith");
        }
        return Reflect.flag(this.isAlly, team, player);
    }

    private static Class<?> classOf(Object value) {
        return value == null ? null : value.getClass();
    }
}

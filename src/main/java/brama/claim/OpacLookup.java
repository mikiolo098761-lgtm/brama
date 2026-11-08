package brama.claim;

import brama.Brama;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Open Parties and Claims — Xaero's claim mod, which keeps claims and parties in one place.
 *
 * <p>Claims there belong to a player rather than to a group, with parties layered on top, so the land
 * question and the diplomacy question are asked of two different managers and joined here: the claim
 * says whose ground the gate stands on, the party says who that owner counts as their own.
 *
 * <p>Bound by reflection; see {@link Reflect}.
 */
final class OpacLookup implements ClaimLookup {

    static final String ID = "openpartiesandclaims";

    private boolean bound;
    private boolean retired;

    private Object claimsManager;
    private Method claimAt;
    private Method claimPlayerId;

    private Object partyManager;
    private Method partyByMember;
    private Method partyId;
    private Method memberInfo;
    private Method allyParties;
    private Method allyPartyId;

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
            Class<?> apiType = Reflect.type("xaero.pac.common.api.OpenPACServerAPI");
            Object api = Reflect.call(Reflect.method(apiType, 1, "get"), null,
                    net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer());

            this.claimsManager = Reflect.call(
                    Reflect.method(classOf(api), 0, "getServerClaimsManager", "getClaimsManager"), api);
            this.claimAt = Reflect.method(classOf(this.claimsManager), 3, "get");

            this.partyManager = Reflect.call(
                    Reflect.method(classOf(api), 0, "getServerPartyManager", "getPartyManager"), api);
            this.partyByMember = Reflect.method(classOf(this.partyManager), 1,
                    "getPartyByMember", "getPartyByOwner", "getPartyByMemberUUID");

            if (this.claimsManager == null || this.claimAt == null
                    || this.partyManager == null || this.partyByMember == null) {
                retire("Open Parties and Claims is installed but its API does not look the way this "
                        + "version of Brama expects");
                return;
            }
            Brama.LOGGER.info("brama: siege watch is reading claims and parties from Open Parties and Claims");
        } catch (Throwable thrown) {
            Brama.LOGGER.debug("brama: Open Parties and Claims lookup failed to bind", thrown);
            retire("Open Parties and Claims could not be reached");
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

        UUID claimOwner = claimOwnerAt(level, pos);
        // Unclaimed, or claimed by a third party: either way, not a boundary this gate defends.
        if (claimOwner == null || !claimOwner.equals(owner)) {
            return Verdict.UNKNOWN;
        }

        return welcome(owner, player.getUUID()) ? Verdict.WELCOME : Verdict.INTRUDER;
    }

    @Override
    public boolean areAllies(ServerLevel level, UUID owner, ServerPlayer player) {
        if (owner == null || !available()) {
            return false;
        }
        return welcome(owner, player.getUUID());
    }

    private UUID claimOwnerAt(ServerLevel level, BlockPos pos) {
        Object claim = Reflect.unwrap(Reflect.call(this.claimAt, this.claimsManager,
                level.dimension().location(),
                SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ())));
        if (claim == null) {
            return null;
        }
        if (this.claimPlayerId == null) {
            this.claimPlayerId = Reflect.method(claim.getClass(), 0, "getPlayerId", "getOwner", "getPlayerUUID");
        }
        return Reflect.call(this.claimPlayerId, claim) instanceof UUID id ? id : null;
    }

    /** The owner themself, a fellow party member, or a member of a party the owner's is allied to. */
    private boolean welcome(UUID owner, UUID player) {
        if (owner.equals(player)) {
            return true;
        }

        Object ownerParty = Reflect.unwrap(Reflect.call(this.partyByMember, this.partyManager, owner));
        if (ownerParty == null) {
            return false;
        }

        if (this.memberInfo == null) {
            this.memberInfo = Reflect.method(ownerParty.getClass(), 1, "getMemberInfo", "getMember", "isMember");
        }
        Object member = Reflect.unwrap(Reflect.call(this.memberInfo, ownerParty, player));
        if (member instanceof Boolean isMember) {
            if (isMember) {
                return true;
            }
        } else if (member != null) {
            return true;
        }

        return alliedTo(ownerParty, player);
    }

    private boolean alliedTo(Object ownerParty, UUID player) {
        Object playerParty = Reflect.unwrap(Reflect.call(this.partyByMember, this.partyManager, player));
        if (playerParty == null) {
            return false;
        }
        if (this.partyId == null) {
            this.partyId = Reflect.method(playerParty.getClass(), 0, "getId", "getPartyId", "getUuid");
        }
        if (!(Reflect.call(this.partyId, playerParty) instanceof UUID playerPartyId)) {
            return false;
        }

        if (this.allyParties == null) {
            this.allyParties = Reflect.method(ownerParty.getClass(), 0, "getAllyParties", "getAllies");
        }
        Object allies = Reflect.call(this.allyParties, ownerParty);
        if (allies == null) {
            return false;
        }

        Stream<?> stream = allies instanceof Stream<?> asStream
                ? asStream
                : allies instanceof Iterable<?> iterable
                        ? java.util.stream.StreamSupport.stream(iterable.spliterator(), false)
                        : Stream.empty();

        return stream.anyMatch(ally -> {
            if (ally instanceof UUID id) {
                return id.equals(playerPartyId);
            }
            if (this.allyPartyId == null && ally != null) {
                this.allyPartyId = Reflect.method(ally.getClass(), 0, "getPartyId", "getId", "getUuid");
            }
            return Objects.equals(Reflect.call(this.allyPartyId, ally), playerPartyId);
        });
    }

    private static Class<?> classOf(Object value) {
        return value == null ? null : value.getClass();
    }
}

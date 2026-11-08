package brama.command;

import brama.block.AbstractGateBlock;
import brama.block.GateAssembly;
import brama.blockentity.GateBlockEntity;
import brama.siege.GateEnemies;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.TeamArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.scores.PlayerTeam;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The {@code /gate} tree: ownership, trust and enmity.
 *
 * <p>Everything that is a property of one gate — trust, co-owners — resolves its target the same way,
 * a raytrace from the player's eyes, so those commands never need coordinates and can never touch a
 * gate other than the one in front of you.
 *
 * <p>{@code /gate enemy} is the exception, and deliberately. An enemy is an enemy of <em>you</em>,
 * not of one doorway, so it is kept per player and applies to every gate you own at once — see
 * {@link GateEnemies}.
 */
public final class GateCommand {

    /** How far to look for a gate. Comfortably past vanilla survival reach. */
    private static final double REACH = 6.0D;

    private static final SimpleCommandExceptionType NOT_LOOKING_AT_GATE =
            new SimpleCommandExceptionType(Component.translatable("command.brama.not_looking_at_gate"));
    private static final SimpleCommandExceptionType NO_PERMISSION =
            new SimpleCommandExceptionType(Component.translatable("command.brama.no_permission"));
    private static final SimpleCommandExceptionType NEEDS_CREATIVE =
            new SimpleCommandExceptionType(Component.translatable("command.brama.needs_creative"));

    private GateCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("gate")
                .then(Commands.literal("trust")
                        // Brigadier matches literals before arguments, so a player literally named
                        // "team" cannot be trusted through this branch. Acceptable trade-off.
                        .then(Commands.literal("team")
                                .then(Commands.argument("team", TeamArgument.team())
                                        .executes(context -> setTeam(context, true))))
                        .then(Commands.argument("players", GameProfileArgument.gameProfile())
                                .executes(context -> setTrusted(context, true))))
                .then(Commands.literal("untrust")
                        .then(Commands.literal("team")
                                .then(Commands.argument("team", TeamArgument.team())
                                        .executes(context -> setTeam(context, false))))
                        .then(Commands.argument("players", GameProfileArgument.gameProfile())
                                .executes(context -> setTrusted(context, false))))
                .then(Commands.literal("op")
                        .then(Commands.argument("players", GameProfileArgument.gameProfile())
                                .executes(context -> setOp(context, true))))
                .then(Commands.literal("unop")
                        .then(Commands.argument("players", GameProfileArgument.gameProfile())
                                .executes(context -> setOp(context, false))))

                .then(Commands.literal("enemy")
                        .then(Commands.literal("add")
                                .then(Commands.argument("players", GameProfileArgument.gameProfile())
                                        .executes(context -> setEnemy(context, true))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("players", GameProfileArgument.gameProfile())
                                        .executes(context -> setEnemy(context, false))))
                        .then(Commands.literal("list")
                                .executes(GateCommand::listEnemies)))

                .then(Commands.literal("info")
                        .executes(GateCommand::info)));
    }

    private static int setTrusted(CommandContext<CommandSourceStack> context, boolean add) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        GateBlockEntity gate = lookedAtGate(player);
        if (!gate.canManage(player)) {
            throw refusal(player);
        }

        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(context, "players");
        StringBuilder names = new StringBuilder();
        int changed = 0;
        for (GameProfile profile : profiles) {
            if (gate.setTrusted(profile.getId(), add)) {
                changed++;
                append(names, profile.getName());
            }
        }

        return report(context, changed, names,
                add ? "command.brama.trusted" : "command.brama.untrusted");
    }

    private static int setOp(CommandContext<CommandSourceStack> context, boolean add) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        GateBlockEntity gate = lookedAtGate(player);
        if (!gate.canPromote(player)) {
            throw refusal(player);
        }

        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(context, "players");
        StringBuilder names = new StringBuilder();
        int changed = 0;
        for (GameProfile profile : profiles) {
            if (gate.setOp(profile.getId(), add)) {
                changed++;
                append(names, profile.getName());
            }
        }

        return report(context, changed, names,
                add ? "command.brama.opped" : "command.brama.unopped");
    }

    private static int setTeam(CommandContext<CommandSourceStack> context, boolean add) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        GateBlockEntity gate = lookedAtGate(player);
        if (!gate.canManage(player)) {
            throw refusal(player);
        }

        PlayerTeam team = TeamArgument.getTeam(context, "team");
        StringBuilder names = new StringBuilder();
        int changed = gate.setTrustedTeam(team.getName(), add) ? 1 : 0;
        if (changed > 0) {
            append(names, team.getName());
        }

        return report(context, changed, names,
                add ? "command.brama.trusted_team" : "command.brama.untrusted_team");
    }

    /**
     * Declares war, for every gate the caller owns at once.
     *
     * <p>Needs no gate in front of you and no permission check beyond being a player: the list is
     * your own, and it can only ever shut your own gates.
     */
    private static int setEnemy(CommandContext<CommandSourceStack> context, boolean add) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        GateEnemies enemies = GateEnemies.of(player.server);

        StringBuilder names = new StringBuilder();
        int changed = 0;
        for (GameProfile profile : GameProfileArgument.getGameProfiles(context, "players")) {
            // Declaring war on yourself would lock you out of your own castle.
            if (profile.getId().equals(player.getUUID())) {
                continue;
            }
            if (enemies.set(player.getUUID(), profile.getId(), add)) {
                changed++;
                append(names, profile.getName());
            }
        }

        return report(context, changed, names,
                add ? "command.brama.enemy_added" : "command.brama.enemy_removed");
    }

    private static int listEnemies(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        Set<UUID> listed = GateEnemies.of(player.server).listed(player.getUUID());

        source.sendSuccess(() -> Component.translatable("command.brama.enemy_list", names(source, listed)), false);
        return listed.size();
    }

    private static int info(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        GateBlockEntity gate = lookedAtGate(source.getPlayerOrException());

        String owner = gate.isUnowned()
                ? Component.translatable("command.brama.unowned").getString()
                : gate.ownerName();

        source.sendSuccess(() -> Component.translatable("command.brama.info_owner", owner), false);
        source.sendSuccess(() -> Component.translatable("command.brama.info_ops", names(source, gate.ops())), false);
        source.sendSuccess(() -> Component.translatable("command.brama.info_trusted", names(source, gate.trusted())), false);
        source.sendSuccess(() -> Component.translatable("command.brama.info_teams", list(gate.trustedTeams())), false);
        source.sendSuccess(() -> Component.translatable("command.brama.info_size",
                gate.size().getSerializedName()), false);
        return 1;
    }

    /**
     * Resolves the assembled gate the player is looking at.
     *
     * <p>Note an open gate has no outline shape in its middle column, so the ray passes straight
     * through the doorway — aim at a closed gate, or at one of the hinge columns.
     */
    private static GateBlockEntity lookedAtGate(ServerPlayer player) throws CommandSyntaxException {
        HitResult hit = player.pick(REACH, 1.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            throw NOT_LOOKING_AT_GATE.create();
        }

        Level level = player.level();
        BlockPos pos = ((BlockHitResult) hit).getBlockPos();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof AbstractGateBlock) || !state.getValue(AbstractGateBlock.ASSEMBLED)) {
            throw NOT_LOOKING_AT_GATE.create();
        }

        BlockEntity blockEntity = level.getBlockEntity(GateAssembly.originOf(state, pos));
        if (!(blockEntity instanceof GateBlockEntity gate)) {
            throw NOT_LOOKING_AT_GATE.create();
        }
        return gate;
    }

    /**
     * Why the command was refused.
     *
     * <p>An operator in survival mode is told about the creative-mode requirement rather than simply
     * that they lack permission, because from their side the two look identical and the first thing
     * they would otherwise conclude is that the mod is broken.
     */
    private static CommandSyntaxException refusal(Player player) {
        return GateBlockEntity.hasAdminLevel(player) && !player.isCreative()
                ? NEEDS_CREATIVE.create()
                : NO_PERMISSION.create();
    }

    private static int report(CommandContext<CommandSourceStack> context, int changed, StringBuilder names, String key) {
        if (changed == 0) {
            context.getSource().sendFailure(Component.translatable("command.brama.no_change"));
            return 0;
        }
        String listed = names.toString();
        context.getSource().sendSuccess(() -> Component.translatable(key, listed), false);
        return changed;
    }

    private static void append(StringBuilder builder, String name) {
        if (builder.length() > 0) {
            builder.append(", ");
        }
        builder.append(name);
    }

    /** Turns stored UUIDs back into names via the server's profile cache, for {@code /gate info}. */
    private static String names(CommandSourceStack source, Set<UUID> ids) {
        if (ids.isEmpty()) {
            return Component.translatable("command.brama.none").getString();
        }
        return ids.stream()
                .map(id -> source.getServer().getProfileCache() == null
                        ? id.toString()
                        : source.getServer().getProfileCache().get(id)
                                .map(GameProfile::getName)
                                .orElse(id.toString()))
                .collect(Collectors.joining(", "));
    }

    private static String list(Set<String> values) {
        return values.isEmpty()
                ? Component.translatable("command.brama.none").getString()
                : String.join(", ", values);
    }
}

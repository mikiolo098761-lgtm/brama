package brama.item;

import brama.Config;
import brama.block.AbstractGateBlock;
import brama.block.GateAssembly;
import brama.blockentity.GateBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The tool that links a 3x3 of loose gate blocks into a working gate.
 *
 * <p>Right-click a loose gate block to assemble. Sneak + right-click an assembled one to take it
 * apart again — sneaking is what makes this reachable, because otherwise
 * {@link MedievalGateBlock#use} claims the interaction first and just opens the gate.
 */
public class GateHammerItem extends Item {

    public GateHammerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);

        if (!(state.getBlock() instanceof AbstractGateBlock)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        Player player = context.getPlayer();

        if (state.getValue(AbstractGateBlock.ASSEMBLED)) {
            BlockPos origin = GateAssembly.originOf(state, pos);

            // Without this the whole ownership system is bypassable in two clicks: take someone's
            // gate apart, put it back together, and the hammer's wielder becomes the new owner.
            if (Config.protectDisassembly()
                    && player != null
                    && level.getBlockEntity(origin) instanceof GateBlockEntity gate
                    && !gate.canManage(player)) {
                player.displayClientMessage(Component.translatable("message.brama.gate_locked", gate.ownerName()), true);
                return InteractionResult.CONSUME;
            }

            GateAssembly.disassemble(level, origin, state, null);
            level.playSound(null, pos, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 0.8F, 0.7F);
            damageHammer(context, player);
            return InteractionResult.CONSUME;
        }

        if (GateAssembly.tryAssemble(level, pos, player)) {
            damageHammer(context, player);
            return InteractionResult.CONSUME;
        }

        // Tell the player *why* nothing happened, rather than leaving them guessing.
        if (player != null) {
            player.displayClientMessage(Component.translatable("message.brama.gate_incomplete"), true);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public int getMaxDamage(ItemStack stack) {
        // The durability baked in at registration cannot change, so it is overridden per-stack.
        return Config.hammerDurability();
    }

    private static void damageHammer(UseOnContext context, Player player) {
        if (player != null) {
            context.getItemInHand().hurtAndBreak(1, player, broken -> broken.broadcastBreakEvent(context.getHand()));
        }
    }
}

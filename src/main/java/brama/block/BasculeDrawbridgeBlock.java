package brama.block;

import brama.Config;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/**
 * The later drawbridge design: two overhead beams pivoting on the wall above the gateway, chained to
 * the deck's far corners, with counterweights on their inner ends. Beams and deck swing together as a
 * parallelogram linkage, which is what keeps the chains taut through the whole motion.
 *
 * <p>Mechanically identical to the plain {@link DrawbridgeBlock} — same relocating deck, same
 * clearance rules, same fall direction. Only its balance, its sounds and the arms it draws differ.
 */
public class BasculeDrawbridgeBlock extends DrawbridgeBlock {

    public BasculeDrawbridgeBlock(Properties properties, GateWood wood) {
        super(properties, wood);
    }

    @Override
    public Config.GateSettings settings() {
        return Config.BASCULE_DRAWBRIDGE;
    }

    @Override
    public boolean hasSupportArms() {
        return true;
    }

    @Override
    protected SoundEvent openSound() {
        return SoundEvents.WOODEN_TRAPDOOR_OPEN;
    }

    @Override
    protected SoundEvent closeSound() {
        return SoundEvents.WOODEN_TRAPDOOR_CLOSE;
    }
}

package brama.registry;

import brama.Brama;
import brama.block.GateWood;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Brama.MODID);

    public static final RegistryObject<CreativeModeTab> BRAMA = CREATIVE_MODE_TABS.register("brama",
            () -> CreativeModeTab.builder()
                    // Deliberately unanchored, which puts this on the second page: Forge appends
                    // tabs that state no position after every vanilla one, and vanilla's own tabs
                    // exactly fill the first page.
                    //
                    // Do not reach for withTabsBefore/withTabsAfter to place it. Anchoring inside
                    // the vanilla list displaces a vanilla tab rather than moving this one, and
                    // anchoring to either end throws CyclePresentException at startup, because the
                    // vanilla tabs already constrain each other and the extra edge closes a loop.
                    .title(Component.translatable("itemGroup.brama"))
                    .icon(() -> new ItemStack(ModItems.MEDIEVAL_GATE.get()))
                    .displayItems((parameters, output) -> {
                        // Grouped by gate type rather than by wood, so the tab reads as four kinds
                        // of gate in eleven timbers and not as eleven near-identical sets.
                        for (GateWood wood : GateWood.values()) {
                            output.accept(ModItems.medievalGate(wood).get());
                        }
                        output.accept(ModItems.PORTCULLIS.get());
                        for (GateWood wood : GateWood.values()) {
                            output.accept(ModItems.drawbridge(wood).get());
                        }
                        for (GateWood wood : GateWood.values()) {
                            output.accept(ModItems.basculeDrawbridge(wood).get());
                        }
                        output.accept(ModItems.GATE_HAMMER.get());
                    })
                    .build());

    private ModCreativeTabs() {
    }
}

package brama.registry;

import brama.Brama;
import brama.block.GateWood;
import brama.item.GateHammerItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.EnumMap;
import java.util.Map;

public final class ModItems {

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Brama.MODID);

    private static final Map<GateWood, RegistryObject<Item>> MEDIEVAL_GATES = new EnumMap<>(GateWood.class);
    private static final Map<GateWood, RegistryObject<Item>> DRAWBRIDGES = new EnumMap<>(GateWood.class);
    private static final Map<GateWood, RegistryObject<Item>> BASCULE_DRAWBRIDGES = new EnumMap<>(GateWood.class);

    public static final RegistryObject<Item> PORTCULLIS = ITEMS.register("portcullis",
            () -> new BlockItem(ModBlocks.PORTCULLIS.get(), new Item.Properties()));

    static {
        for (GateWood wood : GateWood.values()) {
            MEDIEVAL_GATES.put(wood, ITEMS.register(wood.gateId(),
                    () -> new BlockItem(ModBlocks.medievalGate(wood).get(), new Item.Properties())));
            DRAWBRIDGES.put(wood, ITEMS.register(wood.drawbridgeId(),
                    () -> new BlockItem(ModBlocks.drawbridge(wood).get(), new Item.Properties())));
            BASCULE_DRAWBRIDGES.put(wood, ITEMS.register(wood.basculeId(),
                    () -> new BlockItem(ModBlocks.basculeDrawbridge(wood).get(), new Item.Properties())));
        }
    }

    public static final RegistryObject<Item> MEDIEVAL_GATE = MEDIEVAL_GATES.get(GateWood.OAK);
    public static final RegistryObject<Item> DRAWBRIDGE = DRAWBRIDGES.get(GateWood.OAK);
    public static final RegistryObject<Item> BASCULE_DRAWBRIDGE = BASCULE_DRAWBRIDGES.get(GateWood.OAK);

    public static final RegistryObject<Item> GATE_HAMMER = ITEMS.register("gate_hammer",
            () -> new GateHammerItem(new Item.Properties().durability(256)));

    public static RegistryObject<Item> medievalGate(GateWood wood) {
        return MEDIEVAL_GATES.get(wood);
    }

    public static RegistryObject<Item> drawbridge(GateWood wood) {
        return DRAWBRIDGES.get(wood);
    }

    public static RegistryObject<Item> basculeDrawbridge(GateWood wood) {
        return BASCULE_DRAWBRIDGES.get(wood);
    }

    private ModItems() {
    }
}

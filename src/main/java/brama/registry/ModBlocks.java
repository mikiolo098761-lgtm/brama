package brama.registry;

import brama.Brama;
import brama.block.AbstractGateBlock;
import brama.block.BasculeDrawbridgeBlock;
import brama.block.DrawbridgeBlock;
import brama.block.GateWood;
import brama.block.MedievalGateBlock;
import brama.block.PortcullisBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Brama.MODID);

    /**
     * The wooden gates and both bridges, one block per {@link GateWood}.
     *
     * <p>Filled by the static initialiser below rather than written out eleven times each: the only
     * thing that differs between one wood's gate and another's is the texture family it names, which
     * the block takes as a constructor argument.
     */
    private static final Map<GateWood, RegistryObject<MedievalGateBlock>> MEDIEVAL_GATES =
            new EnumMap<>(GateWood.class);
    private static final Map<GateWood, RegistryObject<DrawbridgeBlock>> DRAWBRIDGES =
            new EnumMap<>(GateWood.class);
    private static final Map<GateWood, RegistryObject<BasculeDrawbridgeBlock>> BASCULE_DRAWBRIDGES =
            new EnumMap<>(GateWood.class);

    public static final RegistryObject<PortcullisBlock> PORTCULLIS = BLOCKS.register("portcullis",
            () -> new PortcullisBlock(gateProperties().mapColor(MapColor.METAL).sound(SoundType.METAL)));

    static {
        for (GateWood wood : GateWood.values()) {
            MEDIEVAL_GATES.put(wood, BLOCKS.register(wood.gateId(),
                    () -> new MedievalGateBlock(woodProperties(), wood)));
            DRAWBRIDGES.put(wood, BLOCKS.register(wood.drawbridgeId(),
                    () -> new DrawbridgeBlock(woodProperties(), wood)));
            BASCULE_DRAWBRIDGES.put(wood, BLOCKS.register(wood.basculeId(),
                    () -> new BasculeDrawbridgeBlock(woodProperties(), wood)));
        }
    }

    /** Oak, which is the original mod's gate and keeps its unprefixed id. */
    public static final RegistryObject<MedievalGateBlock> MEDIEVAL_GATE = MEDIEVAL_GATES.get(GateWood.OAK);
    public static final RegistryObject<DrawbridgeBlock> DRAWBRIDGE = DRAWBRIDGES.get(GateWood.OAK);
    public static final RegistryObject<BasculeDrawbridgeBlock> BASCULE_DRAWBRIDGE =
            BASCULE_DRAWBRIDGES.get(GateWood.OAK);

    public static RegistryObject<MedievalGateBlock> medievalGate(GateWood wood) {
        return MEDIEVAL_GATES.get(wood);
    }

    public static RegistryObject<DrawbridgeBlock> drawbridge(GateWood wood) {
        return DRAWBRIDGES.get(wood);
    }

    public static RegistryObject<BasculeDrawbridgeBlock> basculeDrawbridge(GateWood wood) {
        return BASCULE_DRAWBRIDGES.get(wood);
    }

    /**
     * Every gate block there is, for the one block entity type they all share and for the client's
     * list of texture families to go looking for.
     */
    public static List<AbstractGateBlock> allGates() {
        List<AbstractGateBlock> all = new ArrayList<>();
        all.add(PORTCULLIS.get());
        for (GateWood wood : GateWood.values()) {
            all.add(MEDIEVAL_GATES.get(wood).get());
            all.add(DRAWBRIDGES.get(wood).get());
            all.add(BASCULE_DRAWBRIDGES.get(wood).get());
        }
        return Collections.unmodifiableList(all);
    }

    /**
     * Both gate types register at {@link AbstractGateBlock#DECLARED_HARDNESS}; their configured
     * hardness is applied by scaling against it, because {@code strength} is fixed at construction.
     *
     * <p>The blast resistance here is deliberately <b>low</b>, and that is what makes siege mods work.
     * It used to be 1200, on the reasoning that
     * {@link AbstractGateBlock#getExplosionResistance(net.minecraft.world.level.block.state.BlockState,
     * net.minecraft.world.level.BlockGetter, net.minecraft.core.BlockPos,
     * net.minecraft.world.level.Explosion) the per-explosion override} would lower it whenever a gate
     * ought to be breachable. Only the vanilla path consults that override. A cannon mod carrying its
     * own {@code ExplosionDamageCalculator}, or simply looping over blocks itself, reads this static
     * value instead, saw obsidian, and left the gate alone — which is why such mods appeared to work
     * only sometimes.
     *
     * <p>Immunity no longer rests on the number being large. {@code onBlockExploded} decides whether a
     * blast counts, so a gate that should shrug one off still does, whichever route the explosion took
     * to reach it.
     */
    private static BlockBehaviour.Properties gateProperties() {
        return BlockBehaviour.Properties.of()
                .strength(AbstractGateBlock.DECLARED_HARDNESS, AbstractGateBlock.DECLARED_BLAST_RESISTANCE)
                .requiresCorrectToolForDrops()
                // Assembled gates are drawn by a BlockEntityRenderer, and open ones have no
                // collision at all, so they must never be treated as full occluding cubes.
                .noOcclusion();
    }

    /**
     * Timber, for every wood. Deliberately one set of values rather than per-wood ones: a spruce gate
     * is no tougher than a birch one, and a castle should be built from whichever wood suits it
     * rather than from whichever wood the numbers favour.
     */
    private static BlockBehaviour.Properties woodProperties() {
        return gateProperties().mapColor(MapColor.WOOD).sound(SoundType.WOOD);
    }

    private ModBlocks() {
    }
}

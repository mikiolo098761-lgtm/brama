package brama.registry;

import brama.Brama;
import brama.block.AbstractGateBlock;
import brama.blockentity.GateBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, Brama.MODID);

    /**
     * One type serves every gate there is: the origin's block entity is identical for all of them,
     * whatever the gate type and whatever the wood.
     *
     * <p>The registry name stays {@code medieval_gate} even though the type is now generic. Block
     * entity ids are written into save data, so renaming one makes every existing gate fail to load
     * its block entity — losing its owner and, because only the origin draws the gate, leaving an
     * invisible solid wall behind.
     *
     * <p>The valid-block list is taken from the registry rather than written out, because there are
     * now thirty-four of them and a gate block missing from it would throw the moment anyone
     * assembled one.
     */
    public static final RegistryObject<BlockEntityType<GateBlockEntity>> GATE =
            BLOCK_ENTITIES.register("medieval_gate", () -> BlockEntityType.Builder
                    .of(GateBlockEntity::new, gateBlocks())
                    .build(null));

    private static Block[] gateBlocks() {
        List<AbstractGateBlock> gates = ModBlocks.allGates();
        return gates.toArray(new Block[0]);
    }

    private ModBlockEntities() {
    }
}

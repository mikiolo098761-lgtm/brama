package brama.client;

import brama.Brama;
import brama.block.AbstractGateBlock;
import brama.registry.ModBlockEntities;
import brama.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

@Mod.EventBusSubscriber(modid = Brama.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {

    private ClientSetup() {
    }

    /**
     * Models the renderer draws directly rather than through a blockstate have to be registered for
     * baking by hand. Doing so also gets their textures stitched onto the block atlas automatically.
     */
    @SubscribeEvent
    public static void registerModels(ModelEvent.RegisterAdditional event) {
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        GateModels.registerAll(event::register, resources);
        // Piggybacks on the same reload: gate textures need no registering, only discovering, since
        // the whole textures/gate/ directory is stitched by atlases/blocks.json. The families come
        // from the blocks themselves, so a new wood variant needs no mention here.
        GateTextures.discover(resources, families());
    }

    private static List<String> families() {
        return ModBlocks.allGates().stream()
                .map(AbstractGateBlock::textureFamily)
                .distinct()
                .toList();
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.GATE.get(), GateRenderer::new);
    }
}

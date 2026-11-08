package brama.client;

import brama.Brama;
import brama.block.GateSize;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The Blockbench model kits the assembled gates are drawn from, one kit per gate type.
 *
 * <p>These supply <em>shape only</em>. The body pieces are painted by projecting the gate's texture
 * across them (see {@link GateTextures}), so the UVs authored in the JSON are ignored and there are
 * no wear-stage models — damage changes the texture, not the geometry. Detail pieces keep their own
 * authored UVs and their own small texture.
 *
 * <p>Paths are fixed and documented so the artwork can be changed by editing JSON under
 * {@code assets/brama/models/gate/<type>/} without touching any Java. See the readme next to those
 * files.
 *
 * <p>Every piece can be overridden per gate size: drop a {@code body_5x5.json} next to
 * {@code body.json} and 5x5 gates use it while every other size keeps the shared one. Overrides are
 * discovered by probing the pack, so adding one is purely a resource change.
 */
public final class GateModels {

    /** The gate surface itself. Stretched over the whole gate, except on the portcullis. */
    public static final String BODY = "body";

    /** Portcullis detail: the bar closing its right-hand side, which the tiled cell cannot supply. */
    public static final String EDGE = "edge";
    /** Drawbridge detail. */
    public static final String BATTEN = "batten";
    /** Bascule detail. */
    public static final String BEAM = "beam";
    public static final String COUNTERWEIGHT = "counterweight";

    // The wooden gate has no detail pieces: handles belong in the texture, where they can differ per
    // size and cost no geometry.
    public static final Kit WOODEN = new Kit("medieval_gate", List.of(BODY));
    public static final Kit PORTCULLIS = new Kit("portcullis", List.of(BODY, EDGE));
    public static final Kit DRAWBRIDGE = new Kit("drawbridge", List.of(BODY, BATTEN));
    public static final Kit BASCULE =
            new Kit("bascule_drawbridge", List.of(BODY, BATTEN, BEAM, COUNTERWEIGHT));

    private static final Kit[] KITS = {WOODEN, PORTCULLIS, DRAWBRIDGE, BASCULE};

    /** Size-specific files found in the pack. Anything absent here falls back to the shared piece. */
    private static final Set<ResourceLocation> SIZED = new HashSet<>();

    private GateModels() {
    }

    /**
     * Hands every piece to the bakery. Rebuilt from scratch each reload, since a pack that adds or
     * drops a per-size override changes which locations exist.
     */
    public static void registerAll(Consumer<ResourceLocation> register, ResourceManager resources) {
        SIZED.clear();
        PackOrder order = PackOrder.of(resources);
        for (Kit kit : KITS) {
            kit.register(register, resources, order);
        }
    }

    public static BakedModel baked(ResourceLocation location) {
        return Minecraft.getInstance().getModelManager().getModel(location);
    }

    /** One gate type's folder of pieces. */
    public static final class Kit {

        private final String folder;
        private final List<String> pieces;

        private Kit(String folder, List<String> pieces) {
            this.folder = folder;
            this.pieces = pieces;
        }

        public ResourceLocation body(GateSize size) {
            return piece(BODY, size);
        }

        public ResourceLocation piece(String name, GateSize size) {
            ResourceLocation sized = at(name + "_" + size.getSerializedName());
            return SIZED.contains(sized) ? sized : at(name);
        }

        private ResourceLocation at(String name) {
            return new ResourceLocation(Brama.MODID, "gate/" + this.folder + "/" + name);
        }

        private void register(Consumer<ResourceLocation> register, ResourceManager resources, PackOrder order) {
            for (String piece : this.pieces) {
                ResourceLocation base = at(piece);
                register.accept(base);

                // Probing rather than registering all eleven sizes blindly is what keeps the log
                // clean: a model that cannot be resolved logs a load error on every reload. The
                // rank check then drops any that a higher pack has superseded -- see PackOrder.
                for (GateSize size : GateSize.values()) {
                    ResourceLocation sized = at(piece + "_" + size.getSerializedName());
                    if (order.beats(resources, jsonPath(sized), jsonPath(base))) {
                        SIZED.add(sized);
                        register.accept(sized);
                    }
                }
            }
        }

        private static ResourceLocation jsonPath(ResourceLocation model) {
            return new ResourceLocation(model.getNamespace(), "models/" + model.getPath() + ".json");
        }
    }
}

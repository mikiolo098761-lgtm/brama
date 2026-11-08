package brama.client;

import brama.Brama;
import brama.block.GateSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The texture each assembled gate is painted with.
 *
 * <p>One image covers the whole gate. It is <em>not</em> tiled per block: the renderer projects it
 * across the gate's geometry, so a 3x3 gate shows one picture rather than nine copies of one.
 *
 * <p>Images are grouped into <b>families</b>, one per gate appearance — {@code wooden} is the oak
 * swinging gate, {@code spruce_deck} is a spruce bridge deck, {@code portcullis} is the grate. Each
 * block names its own family, and nothing here has a list of them: the families are whatever the
 * registered gate blocks ask for, which is what lets a wood variant be added as a block and a folder
 * of images with no change to this class.
 *
 * <p>Within a family, every size can have its own image at its own resolution:
 *
 * <pre>
 *   textures/gate/wooden.png            fallback, stretched over any size
 *   textures/gate/wooden_3x3.png        48x48  - one pixel per gate pixel
 *   textures/gate/wooden_5x7.png        80x112
 *   textures/gate/wooden_damage2_5x5.png
 * </pre>
 *
 * <p>Anything missing falls back, and the fallback walks two ways: to the unsized image of the same
 * wear stage, and then down through the wear stages to the undamaged one. Both matter. A family may
 * ship sized images only and no unsized one — the wood variants do — and a family may stop short of
 * the last wear stage, which the stock woods do, since no gate type is configured to use five stages
 * out of the box. A gate configured up to five then shows its stage-three artwork at stage four
 * rather than a missing texture.
 *
 * <p>Sizes are discovered by probing the pack, so there is nothing to register — the whole
 * {@code textures/gate/} directory is stitched onto the block atlas by
 * {@code assets/minecraft/atlases/blocks.json}.
 */
public final class GateTextures {

    private static final Map<String, Family> FAMILIES = new HashMap<>();

    /** Per-size images found in the pack. Anything absent falls back as described above. */
    private static final Set<ResourceLocation> SIZED = new HashSet<>();
    /** Unsized images found in the pack, which is the other half of the fallback chain. */
    private static final Set<ResourceLocation> UNSIZED = new HashSet<>();

    private GateTextures() {
    }

    /** The family called {@code base}, created on first use. */
    public static Family of(String base) {
        return FAMILIES.computeIfAbsent(base, Family::new);
    }

    /**
     * Rebuilt each reload, since a pack may add or drop images.
     *
     * @param families every family the registered gate blocks name
     */
    public static void discover(ResourceManager resources, Collection<String> families) {
        SIZED.clear();
        UNSIZED.clear();
        PackOrder order = PackOrder.of(resources);
        for (String base : families) {
            of(base).discover(resources, order);
        }
    }

    /** One gate appearance's family of images. */
    public static final class Family {

        private final String base;

        private Family(String base) {
            this.base = base;
        }

        /**
         * The image to paint a gate of this size and wear with, after both fallbacks.
         *
         * <p>Never returns nothing: if a family has no usable image at all, the undamaged unsized
         * name comes back and Minecraft draws its missing-texture chequerboard, which is the right
         * outcome for a pack that has deleted a file the mod ships.
         */
        public ResourceLocation sprite(GateSize size, int damage) {
            for (int stage = Math.min(damage, GateSize.MAX_DAMAGE_STAGES - 1); stage >= 0; stage--) {
                String name = stage(stage);
                ResourceLocation sized = at(name + "_" + size.getSerializedName());
                if (SIZED.contains(sized)) {
                    return sized;
                }
                ResourceLocation unsized = at(name);
                if (UNSIZED.contains(unsized)) {
                    return unsized;
                }
            }
            return at(this.base);
        }

        private String stage(int damage) {
            return damage == 0 ? this.base : this.base + "_damage" + damage;
        }

        private ResourceLocation at(String name) {
            return new ResourceLocation(Brama.MODID, "gate/" + name);
        }

        private void discover(ResourceManager resources, PackOrder order) {
            boolean any = false;

            for (int damage = 0; damage < GateSize.MAX_DAMAGE_STAGES; damage++) {
                String name = stage(damage);
                ResourceLocation base = at(name);
                ResourceLocation basePath = pngPath(base);

                if (resources.getResource(basePath).isPresent()) {
                    UNSIZED.add(base);
                    any = true;
                }

                for (GateSize size : GateSize.values()) {
                    ResourceLocation sized = at(name + "_" + size.getSerializedName());
                    // Only if it is not outranked by the unsized image -- see PackOrder. This is
                    // what lets a pack reskin every size by supplying one file.
                    if (order.beats(resources, pngPath(sized), basePath)) {
                        SIZED.add(sized);
                        any = true;
                    }
                }
            }

            if (!any) {
                Brama.LOGGER.warn("brama: no images found for gate texture family '{}' under "
                        + "assets/brama/textures/gate/ - those gates will draw untextured", this.base);
            }
        }

        private static ResourceLocation pngPath(ResourceLocation sprite) {
            return new ResourceLocation(sprite.getNamespace(), "textures/" + sprite.getPath() + ".png");
        }
    }
}

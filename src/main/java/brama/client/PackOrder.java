package brama.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.HashMap;
import java.util.Map;

/**
 * Decides whether a per-size override still applies once a resource pack has replaced the file it
 * specialises.
 *
 * <p>Without this the mod would be close to unreskinnable. It ships a texture for all eleven sizes in
 * all five wear stages, and those beat the plain {@code wooden.png} — so a pack that supplied one new
 * base texture would see it ignored on every gate in the game, and would have to replace fifty-five
 * files to change anything.
 *
 * <p>So an override only counts when it comes from a pack sitting at least as high in the stack as
 * the base file it specialises. Replace the base and you replace the whole family; supply sized files
 * of your own and those win instead; override a single size and only that size changes. All three
 * follow from the one comparison.
 *
 * <p>Pack order comes from {@link ResourceManager#listPacks()}, which runs lowest priority first.
 */
final class PackOrder {

    /** Pack id to its position in the stack. Higher wins. */
    private final Map<String, Integer> rank = new HashMap<>();

    private PackOrder(ResourceManager resources) {
        int position = 0;
        for (PackResources pack : resources.listPacks().toList()) {
            this.rank.put(pack.packId(), position++);
        }
    }

    static PackOrder of(ResourceManager resources) {
        return new PackOrder(resources);
    }

    /**
     * @return true when {@code override} exists and is not outranked by {@code base}. A missing base
     *         cannot outrank anything, so a lone sized file is always honoured.
     */
    boolean beats(ResourceManager resources, ResourceLocation override, ResourceLocation base) {
        int candidate = rankOf(resources, override);
        return candidate >= 0 && candidate >= rankOf(resources, base);
    }

    private int rankOf(ResourceManager resources, ResourceLocation path) {
        return resources.getResource(path)
                .map(Resource::sourcePackId)
                .map(id -> this.rank.getOrDefault(id, -1))
                .orElse(-1);
    }
}

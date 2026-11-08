package brama.siege;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who each player has declared an enemy, for the whole world.
 *
 * <p>Per player rather than per gate, which is the opposite of how the rest of a gate's access list
 * works and is the right way round for this one. Trust is a property of a gate — you let the
 * blacksmith through the postern and nowhere else — but war is not: an army either is at war with you
 * or it is not, and nobody wants to name the same besieger on all nine gates of their castle.
 *
 * <p>Kept in the overworld's save folder, since it is one list for the server rather than one per
 * dimension. Names are not stored: the profile cache turns ids back into names for
 * {@code /gate enemy list}, and an id stays right across a rename.
 */
public final class GateEnemies extends SavedData {

    private static final String NAME = "brama_enemies";
    private static final String TAG_OWNERS = "Owners";
    private static final String TAG_OWNER = "Owner";
    private static final String TAG_ENEMIES = "Enemies";

    private final Map<UUID, Set<UUID>> enemies = new HashMap<>();

    public static GateEnemies of(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(GateEnemies::load, GateEnemies::new, NAME);
    }

    /**
     * @param add true to declare, false to withdraw
     * @return whether anything actually changed, so a command can say "nothing changed" honestly
     */
    public boolean set(UUID owner, UUID enemy, boolean add) {
        boolean changed = add
                ? this.enemies.computeIfAbsent(owner, key -> new HashSet<>()).add(enemy)
                : this.enemies.getOrDefault(owner, Set.of()).remove(enemy);
        if (changed) {
            setDirty();
        }
        return changed;
    }

    public boolean isEnemy(UUID owner, UUID suspect) {
        return this.enemies.getOrDefault(owner, Set.of()).contains(suspect);
    }

    public Set<UUID> listed(UUID owner) {
        return Collections.unmodifiableSet(this.enemies.getOrDefault(owner, Set.of()));
    }

    // ------------------------------------------------------------ persistence

    private static GateEnemies load(CompoundTag tag) {
        GateEnemies data = new GateEnemies();
        ListTag owners = tag.getList(TAG_OWNERS, Tag.TAG_COMPOUND);

        for (int i = 0; i < owners.size(); i++) {
            CompoundTag entry = owners.getCompound(i);
            if (!entry.hasUUID(TAG_OWNER)) {
                continue;
            }
            Set<UUID> listed = new HashSet<>();
            ListTag ids = entry.getList(TAG_ENEMIES, Tag.TAG_INT_ARRAY);
            for (int e = 0; e < ids.size(); e++) {
                listed.add(NbtUtils.loadUUID(ids.get(e)));
            }
            data.enemies.put(entry.getUUID(TAG_OWNER), listed);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag owners = new ListTag();
        this.enemies.forEach((owner, listed) -> {
            if (listed.isEmpty()) {
                return;
            }
            CompoundTag entry = new CompoundTag();
            entry.putUUID(TAG_OWNER, owner);

            ListTag ids = new ListTag();
            listed.forEach(enemy -> ids.add(NbtUtils.createUUID(enemy)));
            entry.put(TAG_ENEMIES, ids);
            owners.add(entry);
        });

        tag.put(TAG_OWNERS, owners);
        return tag;
    }
}

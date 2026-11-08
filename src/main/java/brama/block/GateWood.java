package brama.block;

import java.util.Locale;

/**
 * The timbers a wooden gate can be built from, one per vanilla wood type.
 *
 * <p>Each wood is its own block, not a blockstate property, and that is a deliberate and slightly
 * expensive choice. A {@code wood} property would multiply every gate's state count by eleven, and a
 * gate already spends states on {@code part_h}, {@code part_v}, {@code open} and {@code damage}; the
 * footprint was moved off the blockstate and onto the block entity to pay for this (see
 * {@link brama.blockentity.GateBlockEntity#size()}). Separate blocks also give every variant its own
 * item, recipe and loose-block model for free, which a property would each need code to fake.
 *
 * <p>{@link #OAK} is the original mod's gate and keeps its unprefixed ids — {@code brama:medieval_gate},
 * {@code brama:drawbridge}, {@code brama:bascule_drawbridge} — and its original texture family names
 * ({@code wooden}, {@code deck}). Block ids and texture paths are both written into things that
 * outlive a release: ids into every save file, texture paths into every resource pack anyone has
 * made. Renaming them to {@code oak_*} would have bought nothing but tidiness and cost both.
 */
public enum GateWood {

    OAK("oak", "", "wooden", "deck", "minecraft:oak_logs"),
    SPRUCE("spruce", "spruce_", "spruce", "spruce_deck", "minecraft:spruce_logs"),
    BIRCH("birch", "birch_", "birch", "birch_deck", "minecraft:birch_logs"),
    JUNGLE("jungle", "jungle_", "jungle", "jungle_deck", "minecraft:jungle_logs"),
    ACACIA("acacia", "acacia_", "acacia", "acacia_deck", "minecraft:acacia_logs"),
    DARK_OAK("dark_oak", "dark_oak_", "dark_oak", "dark_oak_deck", "minecraft:dark_oak_logs"),
    MANGROVE("mangrove", "mangrove_", "mangrove", "mangrove_deck", "minecraft:mangrove_logs"),
    CHERRY("cherry", "cherry_", "cherry", "cherry_deck", "minecraft:cherry_logs"),
    // Bamboo has no logs: its structural block is the bamboo block, tagged separately.
    BAMBOO("bamboo", "bamboo_", "bamboo", "bamboo_deck", "minecraft:bamboo_blocks"),
    // The nether woods are stems rather than logs, and their tags say so.
    CRIMSON("crimson", "crimson_", "crimson", "crimson_deck", "minecraft:crimson_stems"),
    WARPED("warped", "warped_", "warped", "warped_deck", "minecraft:warped_stems");

    private final String name;
    private final String idPrefix;
    private final String gateTextures;
    private final String deckTextures;
    private final String logTag;

    GateWood(String name, String idPrefix, String gateTextures, String deckTextures, String logTag) {
        this.name = name;
        this.idPrefix = idPrefix;
        this.gateTextures = gateTextures;
        this.deckTextures = deckTextures;
        this.logTag = logTag;
    }

    public String woodName() {
        return this.name;
    }

    /** Registry id of this wood's swinging gate. */
    public String gateId() {
        return this.idPrefix + "medieval_gate";
    }

    public String drawbridgeId() {
        return this.idPrefix + "drawbridge";
    }

    public String basculeId() {
        return this.idPrefix + "bascule_drawbridge";
    }

    /**
     * Texture family for the swinging gate, i.e. the {@code <family>_<size>.png} group under
     * {@code assets/brama/textures/gate/}. See {@link brama.client.GateTextures}.
     */
    public String gateTextures() {
        return this.gateTextures;
    }

    /** Texture family for both drawbridge decks. */
    public String deckTextures() {
        return this.deckTextures;
    }

    /** The item tag this wood's gates are crafted from. */
    public String logTag() {
        return this.logTag;
    }

    /** Title-cased wood name, for generated language entries. */
    public String displayName() {
        StringBuilder out = new StringBuilder();
        for (String word : this.name.split("_")) {
            out.append(out.length() == 0 ? "" : " ")
                    .append(word.substring(0, 1).toUpperCase(Locale.ROOT))
                    .append(word.substring(1));
        }
        return out.toString();
    }
}

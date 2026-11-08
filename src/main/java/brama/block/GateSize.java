package brama.block;

import net.minecraft.util.StringRepresentable;

import java.util.Locale;

/**
 * The gate footprints the mod supports, as a blockstate property value.
 *
 * <p>Width is measured along {@link AbstractGateBlock#AXIS}, height upwards. Even widths are the
 * cleaner case for the swinging gate — 4 splits into two 2-wide leaves with nothing left over, where
 * an odd width leaves a half cell at the seam.
 */
public enum GateSize implements StringRepresentable {

    SIZE_2X3(2, 3),
    SIZE_3X3(3, 3),
    SIZE_3X4(3, 4),
    SIZE_3X5(3, 5),
    SIZE_4X4(4, 4),
    SIZE_4X6(4, 6),
    SIZE_5X3(5, 3),
    SIZE_5X5(5, 5),
    SIZE_5X7(5, 7),
    SIZE_7X7(7, 7),
    SIZE_7X9(7, 9);

    /** Bounds for the {@code part_h} / {@code part_v} properties, across every size. */
    public static final int MAX_WIDTH = 7;
    public static final int MAX_HEIGHT = 9;

    /**
     * Slots reserved for the damage stage.
     *
     * <p>No longer a blockstate property — damage lives on the origin's block entity — but still a
     * compile-time cap, because the renderer indexes a fixed array of wear textures with it.
     */
    public static final int MAX_DAMAGE_STAGES = 5;

    /**
     * Assembly search order, biggest first.
     *
     * <p>Load-bearing rather than cosmetic: every smaller footprint fits inside a larger one, so a
     * 7x9 of loose blocks also contains a valid 3x3. Checking small sizes first would claim a handful
     * of blocks out of the middle of a big gate and leave the rest loose.
     */

    public static final GateSize[] LARGEST_FIRST = {
            SIZE_7X9, SIZE_7X7, SIZE_5X7, SIZE_4X6, SIZE_5X5, SIZE_3X5, SIZE_4X4, SIZE_3X4,
            SIZE_5X3, SIZE_3X3, SIZE_2X3,
    };

    private final String serializedName;
    private final int width;
    private final int height;

    GateSize(int width, int height) {
        this.serializedName = width + "x" + height;
        this.width = width;
        this.height = height;
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    /**
     * How far a portcullis rises when opened: two thirds of its height, rounded down.
     * 3 -> 2, 5 -> 3, 7 -> 4, 9 -> 6, so at least a third of the bars always stays in the doorway.
     */
    public int liftHeight() {
        return this.height * 2 / 3;
    }

    @Override
    public String getSerializedName() {
        return this.serializedName.toLowerCase(Locale.ROOT);
    }
}

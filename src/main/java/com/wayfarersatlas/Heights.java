package com.wayfarersatlas;

/**
 * The height layer of a map chunk keeps a little more than the height: what is on top (water, tree, rock...), how
 * deep the water is and how tall the tree is. Map themes use it (sea charts, height colours, inked outlines).
 * Packed into one int: bits 0-11 height + 2048, 12-14 kind, 15-20 water depth, 21-28 tree height, bit 30 = packed.
 * Older maps hold the plain height; Integer.MIN_VALUE = unknown (cave rock).
 */
public final class Heights {
    public static final int LAND = 0, WATER = 1, TREE = 2, ROCK = 3, SNOW = 4, SAND = 5, LAVA = 6;
    private static final int PACKED = 1 << 30;

    public static int pack(int y, int kind, int depth, int tree) {
        return PACKED | ((Math.max(-2048, Math.min(2047, y)) + 2048) & 0xFFF) | ((kind & 7) << 12)
                | ((Math.max(0, Math.min(63, depth))) << 15) | ((Math.max(0, Math.min(255, tree))) << 21);
    }

    public static boolean packed(int v) {
        return v > 0 && (v & PACKED) != 0;
    }

    /** Height of the top block (Integer.MIN_VALUE if unknown). */
    public static int y(int v) {
        if (v == Integer.MIN_VALUE) return v;
        return packed(v) ? (v & 0xFFF) - 2048 : v;
    }

    public static int kind(int v) {
        return packed(v) ? (v >> 12) & 7 : LAND;
    }

    public static int depth(int v) {
        return packed(v) ? (v >> 15) & 63 : 0;
    }

    /** Height of the ground (under a tree crown). */
    public static int ground(int v) {
        return packed(v) ? y(v) - ((v >> 21) & 255) : y(v);
    }

    private Heights() {}
}

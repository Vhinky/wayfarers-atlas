package com.wayfarersatlas;

import com.wayfarersatlas.AtlasConfig.Theme;

/**
 * Map themes: the same map drawn in another style. Each map pixel is restyled from its colour (and, for the
 * topographic theme, its height), so a theme works on the whole map at once, also on land explored long ago.
 * Colours here are ABGR, like the map textures.
 */
public final class MapThemes {
    private static final int[] PARCH = {227, 209, 166};

    // the world the map shows: sea level, top of the world, contour line step (vanilla: 63, 320, 8)
    private static int sea = 63, top = 320, step = 8;

    /** Fits the topographic scale to the world of this map layer (its sea level and height). */
    public static void prepare(String key) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        String dim = key.contains("#") ? key.substring(0, key.indexOf('#')) : key;
        if (mc.level != null && MapScanner.dimId(mc.level).equals(dim)) {
            sea = mc.level.getSeaLevel();
            top = mc.level.getMaxBuildHeight() - 1;
            if (!mc.level.dimensionType().hasSkyLight()) top = Math.min(top, mc.level.getMinBuildHeight() + mc.level.dimensionType().logicalHeight() - 1); // Nether: below the roof
        } else { // another world, not loaded: the usual Overworld heights
            sea = 63;
            top = 320;
        }
        step = Math.max(4, Math.round((top - sea) / 32f));
    }

    public static Theme current() {
        return AtlasConfig.THEME.get();
    }

    /** Colour of unexplored land. */
    public static int background(Theme t) {
        return switch (t) {
            case CLASSIC -> 0xFF17222B;
            case OLD -> abgr(220, 203, 161);
            case TOPOGRAPHIC -> abgr(216, 199, 158);
            default -> abgr(227, 209, 166);
        };
    }

    /**
     * The restyled colour of one map pixel. c = its colour (ABGR, 0 = nothing), h / hn / hw = its height value and
     * those one pixel north and west (see Heights; Integer.MIN_VALUE if unknown), (bx, bz) = the block it shows.
     */
    public static int apply(Theme t, int c, int h, int hn, int hw, int bx, int bz) {
        if (c == 0 || t == Theme.CLASSIC) return c;
        int r = c & 0xFF, g = (c >> 8) & 0xFF, b = (c >> 16) & 0xFF;
        switch (t) {
            case OLD -> {
                double gray = (r + g + b) / 3.0;
                double nr = r * 0.45 + Math.min(255, gray * 1.07) * 0.55, ng = g * 0.45 + Math.min(255, gray * 0.95) * 0.55,
                        nb = b * 0.45 + gray * 0.74 * 0.55;
                return mixP(nr, ng, nb, 0.15);
            }
            case WATERCOLOR -> {
                // pale colours with soft blotches, as if laid on with a brush
                double blot = 0.5 * smooth(bx / 6.0, bz / 6.0) + 0.5 * smooth(bx / 20.0 + 31, bz / 20.0 + 17);
                double k = 0.9 + 0.2 * blot;
                return abgr((int) ((r * 0.6 + PARCH[0] * 0.4) * k), (int) ((g * 0.6 + PARCH[1] * 0.4) * k),
                        (int) ((b * 0.6 + PARCH[2] * 0.4) * k));
            }
            case TOPOGRAPHIC -> {
                // colour by height, light from the north-west and a thin contour line every 8 blocks
                if (h == Integer.MIN_VALUE) return c;
                boolean water = Heights.packed(h) ? Heights.kind(h) == Heights.WATER : b > r + 25 && b > g; // older maps: by colour
                if (water) {
                    double d = Heights.packed(h) ? Math.min(1, Heights.depth(h) / 25.0) : 0.4;
                    return abgr((int) (150 + (70 - 150) * d), (int) (190 + (110 - 190) * d), (int) (225 + (170 - 225) * d));
                }
                int gy = Heights.ground(h);
                // the colour scale fits the world: from sea level to the top of the world (worlds made taller by
                // mods too); square root, so the lowlands and hills get most of the colours
                double t01 = Math.sqrt(Math.max(0, Math.min(1, (gy - sea) / (double) Math.max(16, top - sea))));
                double[] col = ramp(t01);
                int gn = hn == Integer.MIN_VALUE ? gy : Heights.ground(hn), gw = hw == Integer.MIN_VALUE ? gy : Heights.ground(hw);
                double shade = 1 + Math.max(-0.2, Math.min(0.15, ((gy - gn) + (gy - gw)) * 0.05));
                if (Math.floorDiv(gy, step) != Math.floorDiv(gn, step) || Math.floorDiv(gy, step) != Math.floorDiv(gw, step)) shade *= 0.7;
                return abgr((int) (col[0] * shade), (int) (col[1] * shade), (int) (col[2] * shade));
            }
            default -> {
                return c;
            }
        }
    }

    /** Height colours: lowland green, hills yellow and brown, peaks white. */
    private static double[] ramp(double t) {
        double[][] stops = {{0, 120, 170, 100}, {0.3, 200, 200, 120}, {0.6, 190, 140, 90}, {0.85, 150, 120, 110}, {1, 245, 245, 245}};
        for (int i = 0; i < stops.length - 1; i++) {
            double[] a = stops[i], b = stops[i + 1];
            if (t <= b[0]) {
                double f = (t - a[0]) / (b[0] - a[0]);
                return new double[]{a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f, a[3] + (b[3] - a[3]) * f};
            }
        }
        return new double[]{245, 245, 245};
    }

    /** Smooth value noise 0..1 (for the watercolour blotches). */
    private static double smooth(double x, double z) {
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        double fx = x - x0, fz = z - z0;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        double a = hash(x0, z0), b = hash(x0 + 1, z0), c = hash(x0, z0 + 1), d = hash(x0 + 1, z0 + 1);
        return (a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz;
    }

    private static double hash(int x, int z) {
        int h = x * 374761393 + z * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFF) / 65535.0;
    }

    private static int mixP(double r, double g, double b, double p) {
        return abgr((int) (r * (1 - p) + PARCH[0] * p), (int) (g * (1 - p) + PARCH[1] * p), (int) (b * (1 - p) + PARCH[2] * p));
    }

    static int abgr(int r, int g, int b) {
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));
        return 0xFF000000 | (b << 16) | (g << 8) | r;
    }

    private MapThemes() {}
}

package com.wayfarersatlas;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * 100 pixel-art marker icons (16x16) in groups. The first {@link #TINTED} are neutral shapes that take the
 * marker colour (pins, flags, crosses, beds...), the rest keep their own colours.
 * Each icon has an anchor: the pixel that sits exactly on the marker's coordinates (the tip of a pin,
 * the foot of a flag pole, the centre for everything else).
 */
public final class Icons {
    public static final int COUNT = 100;
    public static final int TINTED = 18;
    /** Groups for the picker: lang key suffix, first index, last index (inclusive). */
    public static final String[] GROUP_KEYS = {"basic", "build", "nature", "res", "mob", "food", "item"};
    public static final int[] GROUP_FROM = {0, 18, 32, 46, 58, 72, 82};
    public static final int[] GROUP_TO = {17, 31, 45, 57, 71, 81, 99};
    /** Markers saved with the old 50-icon set get the closest new icon. */
    private static final int[] OLD_SET = {0, 1, 4, 8, 9, 11, 6, 7, 14, 15, 16, 6, 58, 5, 17, 0, 18, 20, 21, 22, 23, 24, 25, 91, 99, 32, 33, 37, 38, 40, 41, 43, 44, 49, 50, 47, 90, 92, 86, 82, 83, 66, 67, 69, 71, 74, 75, 78, 79, 80};

    /**
     * Icons added by a resource pack: assets/wayfarers_atlas/textures/gui/icons/custom/custom_00.png, custom_01.png...
     * (16x16, numbered without gaps, up to 100). They get the numbers 100, 101... and their own group in the picker.
     */
    public static final int MAX_CUSTOM = 100;
    private static int custom = 0; // how many the loaded resource packs have

    private static final int TEX = 16;
    private static final ResourceLocation[] TEXTURES = new ResourceLocation[COUNT + MAX_CUSTOM];
    private static final float[] AX = new float[COUNT + MAX_CUSTOM];
    private static final float[] AY = new float[COUNT + MAX_CUSTOM];

    static {
        for (int i = 0; i < COUNT + MAX_CUSTOM; i++) {
            TEXTURES[i] = ResourceLocation.fromNamespaceAndPath(WayfarersAtlas.MODID, i < COUNT
                    ? String.format("textures/gui/icons/icon_%02d.png", i)
                    : String.format("textures/gui/icons/custom/custom_%02d.png", i - COUNT));
            AX[i] = 8f;
            AY[i] = 8f;
        }
        AX[0] = 8.0f; AY[0] = 15f;
        AX[8] = 2.5f; AY[8] = 15f;
        AX[9] = 2.5f; AY[9] = 15f;
    }

    /** Resource packs were (re)loaded: count the icons they add. */
    public static void reload(net.minecraft.server.packs.resources.ResourceManager rm) {
        int n = 0;
        while (n < MAX_CUSTOM && rm.getResource(TEXTURES[COUNT + n]).isPresent()) n++;
        custom = n;
    }

    /** Built-in icons plus the ones resource packs add. */
    public static int total() {
        return COUNT + custom;
    }

    /** An icon number that can be drawn: one that is not there (its resource pack is off) becomes the plain pin. */
    public static int fix(int icon) {
        if (icon >= 0 && icon < COUNT + custom) return icon;
        return icon >= COUNT ? 0 : Math.floorMod(icon, COUNT);
    }

    /** Groups of the picker: the built-in ones, then "custom" if a resource pack adds icons. */
    public static int groups() {
        return GROUP_KEYS.length + (custom > 0 ? 1 : 0);
    }

    public static String groupKey(int gi) {
        return gi < GROUP_KEYS.length ? GROUP_KEYS[gi] : "custom";
    }

    public static int groupFrom(int gi) {
        return gi < GROUP_KEYS.length ? GROUP_FROM[gi] : COUNT;
    }

    public static int groupTo(int gi) {
        return gi < GROUP_KEYS.length ? GROUP_TO[gi] : COUNT + custom - 1;
    }

    public static int fromOldSet(int old) {
        return old >= 0 && old < OLD_SET.length ? OLD_SET[old] : 0;
    }

    public static boolean isTinted(int icon) {
        return fix(icon) < TINTED;
    }

    /** Size in GUI pixels that gives a whole number of screen pixels per texel (keeps the pixel art crisp). */
    public static double snappedSize(float desired) {
        double sc = Minecraft.getInstance().getWindow().getGuiScale();
        int k = Math.max(1, (int) Math.round(desired * sc / TEX));
        return TEX * k / sc;
    }

    /** True if (dx, dy), relative to the marker's point on screen, lies on the drawn icon. */
    public static boolean contains(int icon, float desired, double dx, double dy) {
        int idx = fix(icon);
        double s = snappedSize(desired) / TEX;
        double pad = 4; // a little round the icon counts too: easier to hit
        return dx >= -AX[idx] * s - pad && dx <= (TEX - AX[idx]) * s + pad
                && dy >= -AY[idx] * s - pad && dy <= (TEX - AY[idx]) * s + pad;
    }

    /** Draws an icon so that its anchor lies on (x, y). */
    public static void draw(GuiGraphics g, int icon, float x, float y, float desiredSize, int rgb, float alpha) {
        int idx = fix(icon);
        draw0(g, idx, x, y, AX[idx], AY[idx], desiredSize, rgb, alpha);
    }

    /** Draws an icon with its middle on (x, y) - for the picker. */
    public static void drawCentered(GuiGraphics g, int icon, float x, float y, float desiredSize, int rgb, float alpha) {
        draw0(g, fix(icon), x, y, 8f, 8f, desiredSize, rgb, alpha);
    }

    /**
     * A dark edge one screen pixel wide round an icon (its shape drawn black, shifted to all four sides), so it is
     * seen on any background. Draw it before the icon itself.
     */
    public static void drawEdgeCentered(GuiGraphics g, int icon, float x, float y, float desiredSize, float alpha) {
        double px = 1 / Minecraft.getInstance().getWindow().getGuiScale();
        int idx = fix(icon);
        float d = (float) px;
        draw0(g, idx, x - d, y, 8f, 8f, desiredSize, BLACK, alpha);
        draw0(g, idx, x + d, y, 8f, 8f, desiredSize, BLACK, alpha);
        draw0(g, idx, x, y - d, 8f, 8f, desiredSize, BLACK, alpha);
        draw0(g, idx, x, y + d, 8f, 8f, desiredSize, BLACK, alpha);
    }

    private static final int BLACK = Integer.MIN_VALUE; // "rgb" for the plain black shape (edges)

    private static void draw0(GuiGraphics g, int idx, float x, float y, float ax, float ay, float desiredSize, int rgb, float alpha) {
        double sc = Minecraft.getInstance().getWindow().getGuiScale();
        double size = snappedSize(desiredSize);
        double s = size / TEX;
        double x0 = Math.round((x - ax * s) * sc) / sc;
        double y0 = Math.round((y - ay * s) * sc) / sc;
        float r = 1f, gr = 1f, b = 1f;
        if (rgb == BLACK) {
            r = gr = b = 0f;
        } else if (idx < TINTED) {
            r = ((rgb >> 16) & 0xFF) / 255f;
            gr = ((rgb >> 8) & 0xFF) / 255f;
            b = (rgb & 0xFF) / 255f;
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.setColor(r, gr, b, alpha * Ui.alpha);
        g.pose().pushPose();
        g.pose().translate((float) x0, (float) y0, 0f);
        g.pose().scale((float) s, (float) s, 1f);
        g.blit(TEXTURES[idx], 0, 0, 0f, 0f, TEX, TEX, TEX, TEX);
        g.pose().popPose();
        g.setColor(1f, 1f, 1f, Ui.alpha);
    }

    private Icons() {}
}

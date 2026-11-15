package com.wayfarersatlas;

import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

import java.util.Objects;

/**
 * The region you are in (your own regions and the server's). Walking into one shows its name for a few seconds -
 * at the top of the screen, in the middle, or not at all (setting) - and the minimap can show it underneath.
 * Of regions inside each other the smallest one counts.
 */
public final class RegionTitle {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(WayfarersAtlas.MODID, "region_title");
    private static final long IN = 300, HOLD = 2400, OUT = 800;

    private static String current = null; // name of the region you are in
    private static String shown = null;   // the name on the screen
    private static long shownAt = 0;

    /** Name of the region you are in, or null. */
    public static String current() {
        return current;
    }

    public static void reset() {
        current = null;
        shown = null;
    }

    /** A few times per second: which region are you in? */
    public static void tick(Minecraft mc, boolean mapWorks) {
        String name = null;
        if (mapWorks) {
            String dim = MapScanner.dimId(mc.level);
            double x = mc.player.getX(), z = mc.player.getZ(), best = Double.MAX_VALUE;
            for (MarkerStore.Region r : MarkerStore.allRegions()) {
                if (r.off() || !r.dim.equals(dim) || !r.contains(x, z)) continue;
                double a = r.area();
                if (a < best) {
                    best = a;
                    name = r.name;
                }
            }
        }
        if (Objects.equals(name, current)) return;
        current = name;
        if (name != null && AtlasConfig.REGION_TITLE.get() != AtlasConfig.TitlePlace.OFF) {
            shown = name;
            shownAt = Util.getMillis();
        }
    }

    public static void register(RegisterGuiLayersEvent e) {
        e.registerAboveAll(ID, RegionTitle::render);
    }

    private static void render(GuiGraphics g, DeltaTracker dt) {
        Minecraft mc = Minecraft.getInstance();
        if (shown == null || mc.player == null || mc.options.hideGui || mc.screen != null) return;
        AtlasConfig.TitlePlace place = AtlasConfig.REGION_TITLE.get();
        long t = Util.getMillis() - shownAt;
        if (place == AtlasConfig.TitlePlace.OFF || t > IN + HOLD + OUT) {
            shown = null;
            return;
        }
        float a = t < IN ? t / (float) IN : t > IN + HOLD ? 1f - (t - IN - HOLD) / (float) OUT : 1f;
        int alpha = Math.max(5, Math.min(255, (int) (a * 255)));
        var font = mc.font;
        boolean top = place == AtlasConfig.TitlePlace.TOP;
        float scale = top ? 1.25f : 1.75f;
        int tw = font.width(shown);
        float cx = g.guiWidth() / 2f;
        // at the top: under the "Lead me there" plate if it is shown; in the middle: a little above the crosshair
        float y = top ? (Nav.target() != null ? 36 : 12) : g.guiHeight() / 2f - 46;
        g.pose().pushPose();
        g.pose().translate(cx, y, 0);
        g.pose().scale(scale, scale, 1f);
        g.drawString(font, shown, -tw / 2, 0, (alpha << 24) | 0xF3E3B8, true);
        // a short thin gold line on each side of the name
        int line = ((int) (alpha * 0.8f) << 24) | 0xB8934A, dark = ((int) (alpha * 0.5f) << 24) | 0x2B1D10;
        g.fill(-tw / 2 - 22, 4, -tw / 2 - 5, 5, line);
        g.fill(-tw / 2 - 22, 5, -tw / 2 - 5, 6, dark);
        g.fill(tw - tw / 2 + 5, 4, tw - tw / 2 + 22, 5, line);
        g.fill(tw - tw / 2 + 5, 5, tw - tw / 2 + 22, 6, dark);
        g.pose().popPose();
    }

    private RegionTitle() {}
}

package com.wayfarersatlas;

import com.mojang.math.Axis;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/**
 * "Lead me there": a marker you follow. A small parchment plate at the top of the screen shows an arrow, the
 * marker and the distance; on both maps little white chevrons run from you to it.
 * It stops by itself when you arrive. The Nether counts 1:8 with the Overworld.
 */
public final class Nav {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(WayfarersAtlas.MODID, "nav");
    private static Marker target = null;

    public static Marker target() {
        return target;
    }

    public static void set(Marker m) {
        target = m;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.3f, 0.5f));
    }

    public static void clear() {
        target = null;
    }

    public static boolean is(Marker m) {
        return target == m;
    }

    /** The marker's x, z in the given world (Nether and Overworld converted 1:8), or null if it cannot be shown there. */
    public static double[] targetIn(String dim) {
        if (target == null) return null;
        double x = target.x + 0.5, z = target.z + 0.5;
        if (target.dim.equals(dim)) return new double[]{x, z};
        if (pair(target.dim) && pair(dim)) {
            double k = MapScreen.scaleOf(target.dim) / MapScreen.scaleOf(dim);
            return new double[]{x * k, z * k};
        }
        return null;
    }

    private static boolean pair(String d) {
        return d.equals("minecraft:overworld") || d.equals("minecraft:the_nether");
    }

    public static void tick(Minecraft mc) {
        if (target == null) return;
        if (!MarkerStore.MARKERS.contains(target)) { // deleted, or another Atlas
            target = null;
            return;
        }
        if (target.dim.equals(MapScanner.dimId(mc.level))
                && Math.hypot(target.x + 0.5 - mc.player.getX(), target.z + 0.5 - mc.player.getZ()) < 4) {
            mc.player.displayClientMessage(Component.translatable("msg.wayfarers_atlas.arrived", target.name), true);
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.2f, 0.6f));
            target = null;
        }
    }

    // ------------------------------------------------------------------ the plate at the top of the screen

    public static void register(RegisterGuiLayersEvent e) {
        e.registerAboveAll(ID, (g, dt) -> render(g, dt));
    }

    private static void render(GuiGraphics g, DeltaTracker dt) {
        Minecraft mc = Minecraft.getInstance();
        if (target == null || mc.player == null || mc.level == null || mc.options.hideGui || mc.screen != null) return;
        if (!ClientEvents.hasAtlas()) return;
        String here = MapScanner.dimId(mc.level);
        double[] t = targetIn(here);
        float pt = dt.getGameTimeDeltaPartialTick(true);

        String dist;
        float rel = 0;
        if (t != null) {
            double dx = t[0] - mc.player.getX(), dz = t[1] - mc.player.getZ();
            dist = (int) Math.round(Math.hypot(dx, dz)) + " " + Component.translatable("screen.wayfarers_atlas.blocks").getString();
            float bearing = (float) Math.toDegrees(Math.atan2(-dx, dz)); // Minecraft yaw of the direction to it
            rel = bearing - mc.player.getViewYRot(pt);
        } else {
            dist = Component.translatable("screen.wayfarers_atlas.other_world", MapScreen.dimName(target.dim)).getString();
        }

        var font = mc.font;
        String name = target.name;
        int w = Math.max(90, 46 + Math.max(font.width(name), font.width(dist)));
        int h = 26;
        int x = (g.guiWidth() - w) / 2, y = 4;
        // small parchment plate with a thin gold edge
        Ui.roundRect(g, x, y, w, h, 4, 0xFFB8934A);
        Ui.roundGradient(g, x + 1, y + 1, w - 2, h - 2, 3, Ui.PARCH_TOP, Ui.PARCH_BOT);

        // arrow (or a dot in another world)
        int ax = x + 14, ay = y + h / 2;
        if (t != null) {
            g.pose().pushPose();
            g.pose().translate(ax, ay, 0);
            g.pose().mulPose(Axis.ZP.rotationDegrees(rel));
            arrow(g);
            g.pose().popPose();
        } else {
            g.fill(ax - 2, ay - 2, ax + 3, ay + 3, Ui.INK);
        }
        Icons.drawCentered(g, target.icon, x + 34, ay, 14f, target.color, 1f);
        g.drawString(font, name, x + 44, y + 4, Ui.INK, false);
        g.drawString(font, dist, x + 44, y + 14, 0xFF7A5A30, false);
    }

    // pixel arrow, pointing up: K ink outline, G gold, L light gold (the lit side), D dark gold (the shaded side)
    private static final String[] ARROW = {
            "....K....",
            "...KLK...",
            "..KLGDK..",
            ".KLLGDDK.",
            "KLLLGDDDK",
            "KKKLGDKKK",
            "..KLGDK..",
            "..KLGDK..",
            "..KLGDK..",
            "..KKKKK.."};

    /** A crisp pixel arrow in gold with an ink outline, pointing up (turned by the caller). */
    private static void arrow(GuiGraphics g) {
        for (int r = 0; r < ARROW.length; r++) {
            String row = ARROW[r];
            for (int c = 0; c < row.length(); c++) {
                int col = switch (row.charAt(c)) {
                    case 'K' -> Ui.INK;
                    case 'L' -> 0xFFF6DA8A;
                    case 'G' -> 0xFFE0B04A;
                    case 'D' -> 0xFFB07A2A;
                    default -> 0;
                };
                if (col == 0) continue;
                int px = c - 4, py = r - 5;
                g.fill(px, py, px + 1, py + 1, col);
            }
        }
    }

    // ------------------------------------------------------------------ the way on the maps

    /**
     * White chevrons from (ax, ay) to (bx, by) in GUI pixels, cut to the rectangle (x0, y0)-(x1, y1). If round is
     * set, dashes outside the circle in that rectangle are left out (round minimap).
     */
    public static void drawPath(GuiGraphics g, double ax, double ay, double bx, double by,
                                double x0, double y0, double x1, double y1, boolean round) {
        double dx = bx - ax, dy = by - ay, t0 = 0, t1 = 1;
        double[] p = {-dx, dx, -dy, dy}, q = {ax - x0, x1 - ax, ay - y0, y1 - ay};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) return;
            } else {
                double r = q[i] / p[i];
                if (p[i] < 0) t0 = Math.max(t0, r);
                else t1 = Math.min(t1, r);
            }
        }
        if (t0 > t1) return;
        double sc = Minecraft.getInstance().getWindow().getGuiScale();
        double sx = (ax + dx * t0) * sc, sy = (ay + dy * t0) * sc, ex = (ax + dx * t1) * sc, ey = (ay + dy * t1) * sc;
        double len = Math.hypot(ex - sx, ey - sy);
        if (len < 1) return;
        double cx = (x0 + x1) / 2 * sc, cy = (y0 + y1) / 2 * sc, cr = (x1 - x0) / 2 * sc - 2;
        double ang = Math.atan2(ey - sy, ex - sx); // towards the marker
        double gap = 9 * sc, arm = 3.5 * sc;
        float w = (float) Math.max(1, Math.round(sc * 0.67));
        int a = (int) (255 * Ui.alpha);
        int white = (a << 24) | 0xF8F4EA, shadow = ((a * 110 / 255) << 24) | 0x1A1208;
        g.pose().pushPose();
        g.pose().scale((float) (1 / sc), (float) (1 / sc), 1f);
        // little white chevrons pointing the way, with a faint dark edge so they show on snow too
        for (int pass = 0; pass < 2; pass++) {
            for (double u = gap * 0.6; u < len; u += gap) {
                double px = sx + Math.cos(ang) * u, py = sy + Math.sin(ang) * u;
                if (round && Math.hypot(px - cx, py - cy) > cr - arm) continue;
                for (int side = -1; side <= 1; side += 2) {
                    double back = ang + Math.PI + side * 0.65;
                    float th = pass == 0 ? w + 2 : w;
                    g.pose().pushPose();
                    g.pose().translate((float) px, (float) py, 0f);
                    g.pose().mulPose(Axis.ZP.rotation((float) back));
                    fillF(g, pass == 0 ? -1 : 0, -th / 2f, (float) arm + (pass == 0 ? 1 : 0), th / 2f, pass == 0 ? shadow : white);
                    g.pose().popPose();
                }
            }
        }
        g.pose().popPose();
    }

    /** fill() with fractional corners (turned strokes). */
    private static void fillF(GuiGraphics g, float x0, float y0, float x1, float y1, int color) {
        g.pose().pushPose();
        g.pose().translate(x0, y0, 0f);
        g.pose().scale(x1 - x0, y1 - y0, 1f);
        g.fill(0, 0, 1, 1, color);
        g.pose().popPose();
    }

    private Nav() {}
}

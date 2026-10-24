package com.wayfarersatlas;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayDeque;

/** Dotted line of where you walked during the last minute (drawn on both maps). Every Atlas has its own. */
public final class Trail {
    private static final long KEEP_MS = 60_000;
    private record P(double x, double z, long t, boolean gap) {}

    private static final java.util.Map<String, ArrayDeque<P>> BY_ATLAS = new java.util.HashMap<>();
    private static final java.util.Map<String, String> DIM_BY_ATLAS = new java.util.HashMap<>();

    /** Points of the Atlas that is shown now (each Atlas remembers only where it was carried). */
    private static ArrayDeque<P> points() {
        String a = AtlasSync.active();
        return a == null ? null : BY_ATLAS.computeIfAbsent(a, k -> new ArrayDeque<>());
    }

    public static boolean visible() {
        return AtlasConfig.TRAIL.get() && ServerRules.trail();
    }

    /** Called 4 times a second. */
    public static void tick(Minecraft mc) {
        long cut = System.currentTimeMillis() - KEEP_MS;
        for (ArrayDeque<P> q : BY_ATLAS.values()) while (!q.isEmpty() && q.peekFirst().t < cut) q.pollFirst();
        ArrayDeque<P> POINTS = points();
        if (POINTS == null || !ClientEvents.hasAtlas()) return;
        String a = AtlasSync.active();
        String d = MapScanner.dimId(mc.level);
        if (!d.equals(DIM_BY_ATLAS.get(a))) { // a new dimension starts a new trail
            POINTS.clear();
            DIM_BY_ATLAS.put(a, d);
        }
        long now = System.currentTimeMillis();
        double x = mc.player.getX(), z = mc.player.getZ();
        P last = POINTS.peekLast();
        double dist = last == null ? 99 : Math.hypot(x - last.x, z - last.z);
        // a teleport, or a time this Atlas was not carried, breaks the line
        if (dist >= 0.75) POINTS.add(new P(x, z, now, last != null && (dist > 32 || now - last.t > 3000)));
    }

    public static void clear() {
        BY_ATLAS.clear();
        DIM_BY_ATLAS.clear();
    }

    /**
     * Draws the trail of dimension 'viewDim' (nothing if you are in another one). sx / sz turn world x / z into
     * GUI x / y; (curX, curZ) is where you are now, so the line reaches you. Dots keep the same spacing on screen
     * at every zoom and fade with age.
     */
    /** World x, z -> GUI x, y. */
    public interface Proj {
        double[] at(double wx, double wz);
    }

    public static void draw(GuiGraphics g, String viewDim, Proj proj, double curX, double curZ) {
        ArrayDeque<P> POINTS = points();
        if (!visible() || POINTS == null || POINTS.isEmpty() || !viewDim.equals(DIM_BY_ATLAS.get(AtlasSync.active()))) return;
        double sc = Minecraft.getInstance().getWindow().getGuiScale();
        double spacing = 5 * sc;  // real pixels between dots
        int dot = Math.max(2, (int) Math.round(sc * 0.75));
        long now = System.currentTimeMillis();
        g.pose().pushPose();
        g.pose().scale((float) (1 / sc), (float) (1 / sc), 1f); // draw in real screen pixels: crisp dots
        double carry = spacing;
        P prev = null;
        java.util.List<P> pts = new java.util.ArrayList<>(POINTS);
        P tail = POINTS.peekLast();
        pts.add(new P(curX, curZ, now, tail != null && now - tail.t > 3000));
        for (P p : pts) {
            if (prev != null && !p.gap) {
                double[] pa = proj.at(prev.x, prev.z), pb = proj.at(p.x, p.z);
                double ax = pa[0] * sc, ay = pa[1] * sc;
                double bx = pb[0] * sc, by = pb[1] * sc;
                double len = Math.hypot(bx - ax, by - ay);
                float age = Math.min(1f, (now - prev.t) / (float) KEEP_MS);
                int a = (int) (255 * (1f - 0.8f * age) * Ui.alpha);
                double d = spacing - carry;
                while (d <= len) {
                    double t = len == 0 ? 0 : d / len;
                    int px = (int) Math.round(ax + (bx - ax) * t), py = (int) Math.round(ay + (by - ay) * t);
                    g.fill(px - dot / 2 - 1, py - dot / 2 - 1, px - dot / 2 + dot + 1, py - dot / 2 + dot + 1,
                            ((a * 140 / 255) << 24) | 0x1A1208);
                    g.fill(px - dot / 2, py - dot / 2, px - dot / 2 + dot, py - dot / 2 + dot, (a << 24) | 0xFFF6E2);
                    d += spacing;
                }
                carry = len - (d - spacing);
            } else {
                carry = spacing;
            }
            prev = p;
        }
        g.pose().popPose();
    }

    private Trail() {}
}

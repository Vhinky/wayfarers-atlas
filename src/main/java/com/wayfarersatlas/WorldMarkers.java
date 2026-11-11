package com.wayfarersatlas;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Markers in the world, in the Atlas style. Every marker of the world you are in shows as its small, faint icon
 * (name and distance when you look right at it). The marker you are led to ("Lead me there") is fully visible. Close by a marker only becomes more see-through. Drawn on the screen (like Xaero's
 * waypoints), so it is seen through hills, and stays the same size at any distance.
 */
public final class WorldMarkers {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(WayfarersAtlas.MODID, "world_markers");
    private static final int MAX = 48; // nearest markers drawn

    private static final Matrix4f PROJ = new Matrix4f();
    private static final Matrix4f VIEW = new Matrix4f();
    private static Vec3 camPos = Vec3.ZERO;
    private static boolean ready = false;
    private static float zoom = 1f; // how much a zoom mod (or the spyglass) magnifies the world right now

    /** Remembers how the world was looked at this frame (the GUI is drawn afterwards). */
    public static void onStage(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        Camera cam = e.getCamera();
        PROJ.set(e.getProjectionMatrix());
        VIEW.identity().rotation(cam.rotation().conjugate(new Quaternionf()));
        camPos = cam.getPosition();
        // zoom: the camera's field of view against the one set in the options
        double base = 1 / Math.tan(Math.toRadians(Minecraft.getInstance().options.fov().get()) / 2);
        float m11 = Math.abs(PROJ.m11());
        zoom = base > 0 && Float.isFinite(m11) ? (float) (m11 / base) : 1f;
        ready = true;
    }

    public static void register(RegisterGuiLayersEvent e) {
        e.registerBelowAll(ID, WorldMarkers::render);
    }

    /** Screen position (GUI pixels) of a world point, or null if it is behind you. */
    private static double[] project(double x, double y, double z, int gw, int gh) {
        Vector4f v = new Vector4f((float) (x - camPos.x), (float) (y - camPos.y), (float) (z - camPos.z), 1f);
        VIEW.transform(v);
        PROJ.transform(v);
        if (v.w <= 0.05f) return null;
        double nx = v.x / v.w, ny = v.y / v.w;
        return new double[]{(nx + 1) / 2 * gw, (1 - ny) / 2 * gh};
    }

    private static void render(GuiGraphics g, DeltaTracker dt) {
        Minecraft mc = Minecraft.getInstance();
        if (!ready || mc.player == null || mc.level == null || mc.options.hideGui) return;
        boolean all = AtlasConfig.WORLD_MARKERS.get();
        if (!all && Nav.target() == null) return;
        if (!ClientEvents.hasAtlas()) return;
        String dim = MapScanner.dimId(mc.level);
        Vec3 me = mc.player.getEyePosition(dt.getGameTimeDeltaPartialTick(true));
        int gw = g.guiWidth(), gh = g.guiHeight();

        List<Marker> list = new ArrayList<>();
        for (Marker m : MarkerStore.MARKERS) {
            if (m.dim.equals(dim) && !m.hidden && (all || Nav.is(m))) list.add(m);
        }
        list.sort((a, b) -> Double.compare(dist2(b, me), dist2(a, me))); // farthest first, nearest on top
        if (list.size() > MAX) list = list.subList(list.size() - MAX, list.size());

        String unit = Component.translatable("screen.wayfarers_atlas.meters").getString();
        // Positions come only from the camera the world was drawn with (zoom included, whatever mod does it).
        // Anything else that may stretch this layer (some zoom mods do) is undone below, otherwise markers would be
        // zoomed twice and drift away from the places they stand on.
        List<Object[]> shown = new ArrayList<>();
        Marker aimed = null;
        double best = 10;
        for (Marker m : list) {
            double mx = m.x + 0.5, mz = m.z + 0.5;
            double d = Math.sqrt(dist2(m, me));
            float a = (float) Math.max(0.3, Math.min(1, (d - 3) / 8)); // close by: see-through, never gone
            double gy = groundY(mc, m);
            double[] p = project(mx, gy, mz, gw, gh);
            if (p == null) continue;
            float x = (float) p[0], y = (float) p[1]; // not rounded to GUI pixels: no jumping while you move
            if (x < -40 || x > gw + 40 || y < -60 || y > gh + 80) continue;
            shown.add(new Object[]{m, x, y, (int) Math.round(d) + " " + unit, a});
            double c = Math.hypot(x - gw / 2.0, y - 4 - gh / 2.0); // only the one nearest the crosshair gets its name
            if (c < best) {
                best = c;
                aimed = m;
            }
        }
        g.pose().pushPose();
        g.pose().last().pose().identity();
        for (Object[] e : shown) {
            Marker m = (Marker) e[0];
            small(g, m, (float) e[1], (float) e[2], (String) e[3], (float) e[4], m == aimed, Nav.is(m));
        }
        g.pose().popPose();
    }

    private static double dist2(Marker m, Vec3 me) {
        double dx = m.x + 0.5 - me.x, dz = m.z + 0.5 - me.z;
        double dy = m.y == Marker.NO_Y ? 0 : m.y - me.y;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Where the line starts: the marker's height, or the ground there if it has none. */
    private static double groundY(Minecraft mc, Marker m) {
        if (m.y != Marker.NO_Y) return m.y;
        if (mc.level.hasChunk(m.x >> 4, m.z >> 4)) return mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, m.x, m.z);
        return mc.player.getY();
    }

    /**
     * A marker: just its small icon, a little see-through, so it does not stand out. The marker you are led to is
     * fully visible. Looking right at a marker (near the middle of the screen) shows its name
     * and distance underneath in tiny, sharp letters.
     */
    private static void small(GuiGraphics g, Marker m, float x, float y, String dist, float a, boolean aimed, boolean led) {
        float iy = y - 4;
        // one texture pixel = one real screen pixel (two when zoomed in a lot): small at any GUI scale
        float size = (float) (16 * iconScale() / Minecraft.getInstance().getWindow().getGuiScale());
        if (led) glow(g, x, iy, a);
        float ia = a * (aimed || led ? 1f : 0.8f);
        Icons.drawEdgeCentered(g, m.icon, x, iy, size, ia * 0.55f); // thin dark edge: seen on sky, snow and grass
        Icons.drawCentered(g, m.icon, x, iy, size, m.color, ia);
        if (!aimed) return;
        var mc = Minecraft.getInstance();
        var font = mc.font;
        String t = m.name + " \u00b7 " + dist;
        // tiny letters: one font pixel = one or two real screen pixels, on the pixel grid so they stay sharp
        double sc = mc.getWindow().getGuiScale();
        int fp = Math.max(1, (int) Math.floor(Math.max(1, sc / 3) * grow() + 1e-4));
        float k = (float) (fp / sc);
        int tw = font.width(t);
        g.pose().pushPose();
        g.pose().translate(Math.round((x - tw * k / 2) * sc) / sc, Math.round((iy + 8 * iconScale() / sc + 2.5) * sc) / sc, 0);
        g.pose().scale(k, k, 1f);
        g.fill(-2, -1, tw + 1, 9, alpha(0x902B1D10, a));
        g.drawString(font, t, 0, 0, alpha(0xFFF3E3B8, a), false);
        g.pose().popPose();
    }

    /**
     * Zoomed in (zoom mod, spyglass): markers grow a little too, at most 2.5 times for the letters. Sprinting and the
     * like (field of view a bit wider) change nothing.
     */
    private static float grow() {
        return Math.max(1f, Math.min(2.5f, 1f + (zoom - 1.25f) * 0.5f));
    }

    /** Icon size in whole screen pixels per texture pixel (stays sharp): 1, or 2 when zoomed in. */
    private static int iconScale() {
        return grow() >= 1.5f ? 2 : 1;
    }

    /** A thin gold ring round the marker you are led to, gently breathing; only a little bigger than the icon. */
    private static void glow(GuiGraphics g, float x, float y, float a) {
        double sc = Minecraft.getInstance().getWindow().getGuiScale();
        double t = (System.currentTimeMillis() % 1600) / 1600.0;
        float pulse = (float) (0.55 + 0.45 * Math.sin(t * Math.PI * 2));
        int r = 11 * iconScale(); // real pixels
        int cx = (int) Math.round(x * sc), cy = (int) Math.round(y * sc);
        int ring = ((int) (200 * pulse * a) << 24) | 0xFFD86A;
        int soft = ((int) (70 * pulse * a) << 24) | 0xFFD86A;
        g.pose().pushPose();
        g.pose().scale((float) (1 / sc), (float) (1 / sc), 1f);
        for (int dy = -r - 1; dy <= r + 1; dy++) {
            for (int dx = -r - 1; dx <= r + 1; dx++) {
                double d = Math.sqrt(dx * dx + dy * dy);
                if (Math.abs(d - r) < 0.6) g.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, ring);
                else if (Math.abs(d - r) < 1.6) g.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, soft);
            }
        }
        g.pose().popPose();
    }

    private static int alpha(int argb, float a) {
        return ((int) (((argb >>> 24) & 0xFF) * a) << 24) | (argb & 0xFFFFFF);
    }

    private WorldMarkers() {}
}

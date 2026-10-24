package com.wayfarersatlas;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class MapScreen extends Screen {
    private static final MapView VIEW = new MapView(20);
    /** Real screen pixels per block. From 1 up every block is drawn; below 1 coarser levels are used. */
    private static final double[] LEVELS = {0.0625, 0.125, 0.25, 0.5, 1, 2, 3, 4, 6, 8, 12, 16};

    private record Entry(Component label, boolean enabled, Runnable action) {}

    private int mapX, mapY, mapW, mapH;
    private int panelX, panelW = 34;     // height panel (manual cave mode), right of the map
    private int trackTop, trackBot;
    private long flashUntil = 0;         // the panel glows for a moment when manual mode is switched on
    private double cx, cz;               // current view centre
    private double tx, tz;               // target centre (smooth camera)
    private boolean animating = false;
    private long lastMs = Util.getMillis();
    private int levelIdx = -1;
    private double ppb = 2.0;            // animated towards LEVELS[levelIdx]
    private boolean zoomAnim = false;
    private double zwx, zwz, zsx, zsy;   // world point that stays under the cursor while zooming
    private boolean centered = false;
    private boolean holdGuard;           // the open-key is still held: ignore its key repeats
    private boolean draggingSlider = false;

    // ruler: click points on the map, it shows the distance between them in blocks
    private static boolean ruler = false;
    private static final List<double[]> RULER = new ArrayList<>(); // world x, z of each point
    private static String rulerDim = "";
    private double pressX, pressY;
    private boolean pressing = false;

    // world shown on the map and the minimap (null = the one you are in); stays chosen until you switch back
    private static String viewDim = null;

    /** The world chosen with the world button, or null if it is the one you are in. */
    public static String selectedDim(String current) {
        return viewDim == null || viewDim.equals(current) ? null : viewDim;
    }

    public static void resetView() {
        viewDim = null;
    }

    private List<Entry> menu = null;
    private int menuX, menuY, menuW, menuH;

    public MapScreen() {
        super(Component.translatable("screen.wayfarers_atlas.map"));
        holdGuard = Keys.physicallyDown(Keys.OPEN_MAP);
    }

    private String curDim() {
        return MapScanner.dimId(minecraft.level);
    }

    private String shownDim() {
        return viewDim == null ? curDim() : viewDim;
    }

    /** Looking at another dimension than the one you are in. */
    private boolean otherDim() {
        return viewDim != null && !viewDim.equals(curDim());
    }

    /** Blocks of the overworld per block of this dimension (the Nether is 8 times smaller). */
    public static double scaleOf(String dim) {
        return dim.equals("minecraft:the_nether") ? 8.0 : 1.0;
    }

    /** Factor that turns your coordinates into the shown dimension's (in the Nether, 1 block = 8 overworld blocks). */
    private double proj() {
        return scaleOf(curDim()) / scaleOf(shownDim());
    }

    private String shownKey() {
        return otherDim() ? MapScanner.keyFor(viewDim) : MapScanner.viewKey(minecraft.level);
    }

    /** Every world of the server (even not visited yet): the one you are in first, then overworld, Nether, End, modded. */
    private List<String> mappedDims() {
        List<String> out = new ArrayList<>();
        out.add(curDim());
        java.util.TreeSet<String> rest = new java.util.TreeSet<>((a, b) -> {
            int ra = rank(a), rb = rank(b);
            return ra != rb ? Integer.compare(ra, rb) : a.compareTo(b);
        });
        if (minecraft.getConnection() != null) {
            for (var lk : minecraft.getConnection().levels()) rest.add(lk.location().toString());
        }
        for (String k : MapCache.keys()) {
            String d = k.contains("#") ? k.substring(0, k.indexOf('#')) : k;
            var m = MapCache.chunks(k);
            if (m != null && !m.isEmpty()) rest.add(d);
        }
        for (String d : rest) if (!out.contains(d)) out.add(d);
        return out;
    }

    private static int rank(String d) {
        return switch (d) {
            case "minecraft:overworld" -> 0;
            case "minecraft:the_nether" -> 1;
            case "minecraft:the_end" -> 2;
            default -> 3;
        };
    }

    private void toggleRuler() {
        if (ruler) commitRuler(); // switching the ruler off leaves the measured line on the map
        ruler = !ruler;
        RULER.clear();
        rebuildWidgets(); // the button shows whether the ruler is on
    }

    /** Leaves the current ruler on the map (kept in the Atlas, like markers). */
    private static void commitRuler() {
        if (RULER.size() >= 2) {
            List<int[]> pts = new ArrayList<>();
            for (double[] p : RULER) pts.add(new int[]{(int) Math.floor(p[0]), (int) Math.floor(p[1])});
            MarkerStore.RULERS.add(new MarkerStore.Ruler(rulerDim, pts));
            MarkerStore.save();
        }
        RULER.clear();
    }

    @Override
    public void removed() {
        commitRuler(); // closing the map keeps what was measured
        super.removed();
    }

    /** Rulers left on the map: lines, points and the whole length at the end. */
    private void renderSaved(GuiGraphics g, String dim) {
        double sc = sc();
        String b = I18n.get("screen.wayfarers_atlas.blocks");
        for (MarkerStore.Ruler r : MarkerStore.RULERS) {
            if (!r.dim.equals(dim)) continue;
            int total = 0;
            int[] prev = null;
            for (int[] p : r.points) {
                if (prev != null) {
                    line(g, screenX(prev[0] + 0.5), screenY(prev[1] + 0.5), screenX(p[0] + 0.5), screenY(p[1] + 0.5), sc);
                    total += dist(prev[0], prev[1], p[0], p[1]);
                }
                prev = p;
            }
            for (int[] p : r.points) {
                double px = screenX(p[0] + 0.5), py = screenY(p[1] + 0.5);
                g.fill((int) px - 2, (int) py - 2, (int) px + 3, (int) py + 3, 0xFF140C04);
                g.fill((int) px - 1, (int) py - 1, (int) px + 2, (int) py + 2, 0xFFFFF4D0);
            }
            // length of each piece in its middle; for several pieces the whole length under the middle one
            int segs = r.points.size() - 1, midSeg = segs / 2;
            for (int i = 0; i < segs; i++) {
                int[] a = r.points.get(i), c = r.points.get(i + 1);
                double lx = (screenX(a[0] + 0.5) + screenX(c[0] + 0.5)) / 2, ly = (screenY(a[1] + 0.5) + screenY(c[1] + 0.5)) / 2;
                if (lx < mapX - 30 || lx > mapX + mapW + 30 || ly < mapY - 10 || ly > mapY + mapH + 10) continue;
                label(g, dist(a[0], a[1], c[0], c[1]) + " " + b, lx, ly, false);
                if (segs > 1 && i == midSeg) label(g, "\u03a3 " + total + " " + b, lx, ly + 10, true);
            }
        }
    }

    /** The ruler left on the map under the mouse (a point or a line within a few pixels), or null. */
    private MarkerStore.Ruler rulerAt(double mx, double my) {
        String dim = shownDim();
        for (MarkerStore.Ruler r : MarkerStore.RULERS) {
            if (!r.dim.equals(dim)) continue;
            int[] prev = null;
            for (int[] p : r.points) {
                double bx = screenX(p[0] + 0.5), by = screenY(p[1] + 0.5);
                if (Math.hypot(mx - bx, my - by) <= 5) return r;
                if (prev != null) {
                    double ax = screenX(prev[0] + 0.5), ay = screenY(prev[1] + 0.5);
                    double vx = bx - ax, vy = by - ay, l2 = vx * vx + vy * vy;
                    double t = l2 == 0 ? 0 : Mth.clamp(((mx - ax) * vx + (my - ay) * vy) / l2, 0, 1);
                    if (Math.hypot(mx - (ax + vx * t), my - (ay + vy * t)) <= 4) return r;
                }
                prev = p;
            }
        }
        return null;
    }

    private static int dist(double ax, double az, double bx, double bz) {
        return (int) Math.round(Math.hypot(bx - ax, bz - az));
    }

    /**
     * A straight line between two screen points: dark edge, gold middle. Drawn as one turned rectangle (cheap even
     * for very long lines at the closest zoom), cut to the visible map first.
     */
    private void line(GuiGraphics g, double ax, double ay, double bx, double by, double sc) {
        // cut the line to the map area (with a margin), so huge off-screen parts cost nothing
        double x0 = mapX - 4, y0 = mapY - 4, x1 = mapX + mapW + 4, y1 = mapY + mapH + 4;
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
        double sx = (ax + dx * t0) * sc, sy = (ay + dy * t0) * sc, ex = (ax + dx * t1) * sc, ey = (ay + dy * t1) * sc;
        float len = (float) Math.hypot(ex - sx, ey - sy);
        float ang = (float) Math.atan2(ey - sy, ex - sx);
        float w = Math.max(2, Math.round(sc * 0.67f));
        g.pose().pushPose();
        g.pose().scale((float) (1 / sc), (float) (1 / sc), 1f);
        g.pose().translate((float) sx, (float) sy, 0f);
        g.pose().mulPose(com.mojang.math.Axis.ZP.rotation(ang));
        g.pose().scale(1f, 1f, 1f);
        fillF(g, -1, -w / 2f - 1, len + 1, w / 2f + 1, 0xC0140C04);
        fillF(g, 0, -w / 2f, len, w / 2f, 0xFFFFD86A);
        g.pose().popPose();
    }

    /** fill() with fractional corners (the turned ruler line). */
    private static void fillF(GuiGraphics g, float x0, float y0, float x1, float y1, int color) {
        g.pose().pushPose();
        g.pose().translate(x0, y0, 0f);
        g.pose().scale(x1 - x0, y1 - y0, 1f);
        g.fill(0, 0, 1, 1, color);
        g.pose().popPose();
    }

    private void label(GuiGraphics g, String text, double x, double y, boolean bold) {
        float s = 0.75f;
        int tw = font.width(text);
        g.pose().pushPose();
        g.pose().translate((float) x, (float) y, 0f);
        g.pose().scale(s, s, 1f);
        Ui.roundRect(g, -tw / 2 - 3, -6, tw + 6, 12, 3, bold ? 0xE02B1D10 : 0xC02B1D10);
        g.drawString(font, text, -tw / 2, -4, bold ? 0xFFFFE7A0 : 0xFFF3E3B8, false);
        g.pose().popPose();
    }

    /** Ruler points, the lines between them, the distance of each piece and the whole length. */
    private void renderRuler(GuiGraphics g, String dim, int mx, int my, boolean mouseIn) {
        if (!rulerDim.equals(dim)) {
            commitRuler(); // the line measured in the other world stays there
            rulerDim = dim;
        }
        double sc = sc();
        String b = I18n.get("screen.wayfarers_atlas.blocks");
        int total = 0;
        double[] prev = null;
        for (double[] p : RULER) {
            if (prev != null) {
                line(g, screenX(prev[0]), screenY(prev[1]), screenX(p[0]), screenY(p[1]), sc);
                total += dist(prev[0], prev[1], p[0], p[1]);
            }
            prev = p;
        }
        boolean live = mouseIn && prev != null && menu == null;
        double wx = Math.floor(worldX(mx)) + 0.5, wz = Math.floor(worldZ(my)) + 0.5;
        if (live) line(g, screenX(prev[0]), screenY(prev[1]), mx, my, sc);
        prev = null;
        for (double[] p : RULER) { // distance of each piece, in its middle
            if (prev != null) {
                label(g, dist(prev[0], prev[1], p[0], p[1]) + "", (screenX(prev[0]) + screenX(p[0])) / 2,
                        (screenY(prev[1]) + screenY(p[1])) / 2, false);
            }
            prev = p;
        }
        for (double[] p : RULER) { // points
            double px = screenX(p[0]), py = screenY(p[1]);
            g.fill((int) px - 2, (int) py - 2, (int) px + 3, (int) py + 3, 0xFF140C04);
            g.fill((int) px - 1, (int) py - 1, (int) px + 2, (int) py + 2, 0xFFFFF4D0);
        }
        if (live) {
            int seg = dist(prev[0], prev[1], wx, wz);
            String t = RULER.size() > 1 ? seg + " " + b + "  \u00b7  \u03a3 " + (total + seg) : seg + " " + b;
            label(g, t, mx, my - 12, true);
        } else if (RULER.size() > 1) {
            double[] last = RULER.get(RULER.size() - 1);
            label(g, "\u03a3 " + total + " " + b, screenX(last[0]), screenY(last[1]) - 12, true);
        } else if (RULER.isEmpty() && mouseIn && menu == null) {
            label(g, I18n.get("screen.wayfarers_atlas.ruler_hint"), mx, my - 12, false);
        }
    }

    private void nextDim() {
        List<String> dims = mappedDims();
        int i = dims.indexOf(shownDim());
        String next = dims.get((i + 1) % dims.size());
        viewDim = next.equals(curDim()) ? null : next;
        centerOnPlayer(false);
        rebuildWidgets();
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.0f, 0.3f));
    }

    private static double sc() {
        return Minecraft.getInstance().getWindow().getGuiScale();
    }

    private double bpp() {
        return sc() / ppb;
    }

    /** Player heads shrink a little when zoomed out (never below 2/3 of the normal size). */
    private static float headScale(double ppb) {
        return (float) Mth.clamp(0.9 + 0.08 * Math.log(ppb / sc()) / Math.log(2), 0.67, 1.0);
    }

    private static Component caveLabel() {
        return Component.translatable("wayfarers_atlas.cave_mode." + MapScanner.mode().name().toLowerCase());
    }

    @Override
    protected void init() {
        mapX = 26;
        mapY = 36;
        boolean manual = sliderVisible();
        mapW = Math.max(121, width - 52 - (manual ? panelW + 10 : 0)) | 1; // odd: the N plate is exactly centred
        mapH = Math.max(90, height - 36 - 34);
        panelX = mapX + mapW + 15;
        trackTop = mapY + 44;
        trackBot = mapY + mapH - 44;

        if (levelIdx < 0) { // start at roughly one GUI pixel per block, like a normal map
            double s = sc();
            int best = 0;
            for (int i = 0; i < LEVELS.length; i++) {
                if (Math.abs(Math.log(LEVELS[i] / s)) < Math.abs(Math.log(LEVELS[best] / s))) best = i;
            }
            levelIdx = best;
            ppb = LEVELS[best];
        }

        // header, right to left: [settings]  [cave mode]  [+] [-] [to player]
        int cw = 0;
        for (MapScanner.CaveMode m : MapScanner.CaveMode.values()) {
            cw = Math.max(cw, font.width(Component.translatable("wayfarers_atlas.cave_mode." + m.name().toLowerCase())));
        }
        cw += 20;
        int gearX = width - 26 - 20;
        int caveX = gearX - 6 - cw;
        int zoomX = caveX - 6 - 64;

        int rulerX = zoomX - 6 - 20;
        addRenderableWidget(new ParchButton(rulerX, 9, 20, 20, Component.empty(), ParchButton.Kind.RULER, ruler,
                b -> toggleRuler()));

        List<String> dims = mappedDims();
        if (dims.size() > 1) { // switch between the worlds you have mapped
            int dw = 0;
            for (String d : dims) dw = Math.max(dw, font.width(dimName(d)));
            dw += 20;
            addRenderableWidget(new ParchButton(rulerX - 6 - dw, 9, dw, 20, dimName(shownDim()), ParchButton.Kind.TEXT, false,
                    b -> nextDim()));
        }

        addRenderableWidget(new ParchButton(zoomX, 9, 20, 20, Component.empty(), ParchButton.Kind.PLUS, false,
                b -> zoomStep(1, mapX + mapW / 2.0, mapY + mapH / 2.0)));
        addRenderableWidget(new ParchButton(zoomX + 22, 9, 20, 20, Component.empty(), ParchButton.Kind.MINUS, false,
                b -> zoomStep(-1, mapX + mapW / 2.0, mapY + mapH / 2.0)));
        addRenderableWidget(new ParchButton(zoomX + 44, 9, 20, 20, Component.empty(), ParchButton.Kind.TARGET, false,
                b -> centerOnPlayer(true)));
        addRenderableWidget(new ParchButton(caveX, 9, cw, 20, caveLabel(), ParchButton.Kind.TEXT, false, b -> {
            MapScanner.cycleMode(minecraft.player);
            if (sliderVisible()) flashUntil = Util.getMillis() + 1800;
            rebuildWidgets(); // the map makes room for the height panel (or takes it back)
        }));
        addRenderableWidget(new ParchButton(gearX, 9, 20, 20, Component.empty(), ParchButton.Kind.GEAR, false, b ->
                ModList.get().getModContainerById(WayfarersAtlas.MODID).ifPresent(c -> minecraft.setScreen(new ConfigurationScreen(c, this)))));

        if (manual) { // up / down buttons of the height panel
            int bxp = panelX + (panelW - 22) / 2;
            addRenderableWidget(new ParchButton(bxp, mapY + 18, 22, 18, Component.empty(), ParchButton.Kind.PLUS, false,
                    b -> nudgeY(hasShiftDown() ? 8 : 1)));
            addRenderableWidget(new ParchButton(bxp, mapY + mapH - 36, 22, 18, Component.empty(), ParchButton.Kind.MINUS, false,
                    b -> nudgeY(hasShiftDown() ? -8 : -1)));
        }

        if (!centered && minecraft != null && minecraft.player != null) {
            centerOnPlayer(false);
            centered = true;
            if (AtlasConfig.OPEN_SOUND.get()) minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.0f, 0.35f));
        }
    }

    @Override
    public void tick() {
        if (holdGuard && !Keys.physicallyDown(Keys.OPEN_MAP)) holdGuard = false;
    }

    private void centerOnPlayer(boolean smooth) {
        if (minecraft == null || minecraft.player == null) return;
        double k = proj(); // in another world: where you would be there
        if (smooth) {
            tx = minecraft.player.getX() * k;
            tz = minecraft.player.getZ() * k;
            animating = true;
        } else {
            cx = tx = minecraft.player.getX() * k;
            cz = tz = minecraft.player.getZ() * k;
            animating = false;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** No blur: the map behind dialogs must stay visible. */
    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        g.fillGradient(0, 0, width, height, 0xF0241A0E, 0xF0150E06);
    }

    private double worldX(double sx) { return cx + (sx - (mapX + mapW / 2.0)) * bpp(); }
    private double worldZ(double sy) { return cz + (sy - (mapY + mapH / 2.0)) * bpp(); }
    private double screenX(double wx) { return mapX + mapW / 2.0 + VIEW.shiftX + (wx - cx) / bpp(); }
    private double screenY(double wz) { return mapY + mapH / 2.0 + VIEW.shiftY + (wz - cz) / bpp(); }

    /** One zoom step in or out, animated; the point under (sx, sy) stays where it is. */
    private void zoomStep(int dir, double sx, double sy) {
        int ni = Mth.clamp(levelIdx + dir, 0, LEVELS.length - 1);
        if (ni == levelIdx) return;
        zwx = worldX(sx);
        zwz = worldZ(sy);
        zsx = sx;
        zsy = sy;
        levelIdx = ni;
        zoomAnim = true;
        animating = false;
    }

    public static Component dimName(String dim) {
        String path = dim.contains(":") ? dim.substring(dim.indexOf(':') + 1) : dim;
        String key = "wayfarers_atlas.dim." + path;
        if (I18n.exists(key)) return Component.translatable(key);
        String pretty = path.replace('_', ' ');
        return Component.literal(pretty.isEmpty() ? dim : Character.toUpperCase(pretty.charAt(0)) + pretty.substring(1));
    }

    private boolean inMap(double mx, double my) {
        return mx >= mapX && mx < mapX + mapW && my >= mapY && my < mapY + mapH;
    }

    private boolean canTeleport() {
        return minecraft != null && minecraft.player != null && minecraft.player.hasPermissions(2);
    }

    private static void outline(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        g.fill(x0, y0, x1, y0 + 1, color);
        g.fill(x0, y1 - 1, x1, y1, color);
        g.fill(x0, y0 + 1, x0 + 1, y1 - 1, color);
        g.fill(x1 - 1, y0 + 1, x1, y1 - 1, color);
    }

    // ------------------------------------------------------------------ cave slider

    private boolean sliderVisible() {
        return MapScanner.mode() == MapScanner.CaveMode.MANUAL;
    }

    private boolean onSlider(double mx, double my) {
        return sliderVisible() && mx >= panelX && mx <= panelX + panelW && my >= trackTop - 6 && my <= trackBot + 6;
    }

    private void nudgeY(int d) {
        int lo = minecraft.level.getMinBuildHeight(), hi = minecraft.level.getMaxBuildHeight() - 1;
        MapScanner.setManualY(Mth.clamp(MapScanner.manualY() + d, lo, hi));
    }

    private void sliderSet(double my) {
        int lo = minecraft.level.getMinBuildHeight(), hi = minecraft.level.getMaxBuildHeight() - 1;
        double t = Mth.clamp(1.0 - (my - trackTop) / (double) (trackBot - trackTop), 0.0, 1.0);
        MapScanner.setManualY((int) Math.round(lo + t * (hi - lo)));
    }

    private int trackY(int y) {
        int lo = minecraft.level.getMinBuildHeight(), hi = minecraft.level.getMaxBuildHeight() - 1;
        double t = (y - lo) / (double) (hi - lo);
        return (int) Math.round(trackTop + (1.0 - t) * (trackBot - trackTop));
    }

    /** Height panel: label, up button, track with a handle, the current Y, down button. */
    private void renderSlider(GuiGraphics g, int mx, int my) {
        int px = panelX, pw = panelW;
        Ui.frame(g, px - 5, mapY - 5, pw + 10, mapH + 10);
        g.fillGradient(px, mapY, px + pw, mapY + mapH, Ui.PARCH_TOP, Ui.PARCH_BOT);

        long now = Util.getMillis();
        if (now < flashUntil) { // soft pulsing glow right after the switch, so the panel is noticed
            float a = (float) (0.5 + 0.5 * Math.sin(now / 120.0)) * ((flashUntil - now) / 1800f);
            int col = ((int) (a * 200) << 24) | 0xF3D27A;
            outline(g, px - 7, mapY - 7, px + pw + 7, mapY + mapH + 7, col);
            outline(g, px - 8, mapY - 8, px + pw + 8, mapY + mapH + 8, col);
        }

        Component label = Component.translatable("screen.wayfarers_atlas.height");
        Ui.fitCentered(g, font, label.getString(), px + pw / 2f, mapY + 6, pw - 4, Ui.INK, false);

        int cx = px + pw / 2;
        Ui.roundRect(g, cx - 3, trackTop - 3, 6, trackBot - trackTop + 6, 3, 0x553A2410);
        Ui.roundRect(g, cx - 2, trackTop - 2, 4, trackBot - trackTop + 4, 2, 0xFF5A3F22);

        int lo = minecraft.level.getMinBuildHeight(), hi = minecraft.level.getMaxBuildHeight() - 1;
        for (int y = (int) Math.ceil(lo / 64.0) * 64; y <= hi; y += 64) { // a tick every 64 blocks, sea level marked
            int ty = trackY(y);
            g.fill(cx - 7, ty, cx - 4, ty + 1, 0xAA3A2410);
            g.fill(cx + 4, ty, cx + 7, ty + 1, 0xAA3A2410);
        }
        int sea = trackY(minecraft.level.getSeaLevel());
        g.fill(cx - 8, sea, cx - 4, sea + 1, 0xFF4F7CC4);
        g.fill(cx + 4, sea, cx + 8, sea + 1, 0xFF4F7CC4);

        int pyY = trackY(minecraft.player.getBlockY()); // where you are
        g.fill(cx - 9, pyY - 1, cx - 3, pyY + 1, 0xFFFFFFFF);

        int hy = trackY(MapScanner.manualY());
        boolean hot = draggingSlider || onSlider(mx, my);
        Ui.roundRect(g, cx - 9, hy - 4, 18, 8, 3, 0xFF3A2410);
        Ui.roundRect(g, cx - 8, hy - 3, 16, 6, 2, hot ? 0xFFF0D58C : 0xFFE3C77E);

        String ys = Integer.toString(MapScanner.manualY());
        Ui.fitCentered(g, font, ys, px + pw / 2f, mapY + mapH - 48 + 8, pw - 4, Ui.INK, false);
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        long now = Util.getMillis();
        double dt = Math.min(0.1, (now - lastMs) / 1000.0);
        lastMs = now;
        if (zoomAnim) { // smooth zoom: the world point under the cursor stays under the cursor
            double ln = Math.log(ppb), lt = Math.log(LEVELS[levelIdx]);
            double next = ln + (lt - ln) * (1.0 - Math.exp(-dt * 12.0));
            if (Math.abs(lt - next) < 0.004) {
                ppb = LEVELS[levelIdx];
                zoomAnim = false;
            } else {
                ppb = Math.exp(next);
            }
            double b = bpp();
            cx = zwx - (zsx - (mapX + mapW / 2.0)) * b;
            cz = zwz - (zsy - (mapY + mapH / 2.0)) * b;
            tx = cx;
            tz = cz;
            animating = false;
        } else if (animating) { // smooth camera glide (the "to player" button)
            double k = 1.0 - Math.exp(-dt * 9.0);
            cx += (tx - cx) * k;
            cz += (tz - cz) * k;
            if (Math.abs(tx - cx) < 0.05 * bpp() && Math.abs(tz - cz) < 0.05 * bpp()) {
                cx = tx;
                cz = tz;
                animating = false;
            }
        }

        super.render(g, menu != null ? -1000 : mx, menu != null ? -1000 : my, pt); // background + buttons
        Minecraft mc = minecraft;
        if (mc == null || mc.level == null || mc.player == null) return;
        if (viewDim != null && !mappedDims().contains(viewDim)) viewDim = null;
        String dim = shownDim();
        String key = shownKey();
        boolean other = otherDim();
        double k = proj();
        double bpp = bpp();
        float is = MinimapLayer.iconScale(ppb) * AtlasConfig.MARKER_SIZE.get() / 100f;

        g.drawString(font, Component.translatable("screen.wayfarers_atlas.map").withStyle(ChatFormatting.BOLD), 28, 10, Ui.GOLD_LIGHT, true);
        Component sub = MapScanner.isCave() && !other
                ? dimName(dim).copy().append(Component.literal("  \u00b7  ")).append(Component.translatable("wayfarers_atlas.cave"))
                : dimName(dim);
        g.drawString(font, sub, 28, 22, 0xFFB9A57A, false);

        Ui.frame(g, mapX - 5, mapY - 5, mapW + 10, mapH + 10);

        Marker hoverMarker = null;
        AbstractClientPlayer hoverPlayer = null;
        boolean mouseIn = menu == null && !draggingSlider && inMap(mx, my);

        g.enableScissor(mapX, mapY, mapX + mapW, mapY + mapH);
        VIEW.render(g, mapX, mapY, mapW, mapH, cx, cz, bpp, key, mc.player.tickCount);

        // hovered chunk (dark outline, so it is visible on snow too)
        if (mouseIn) {
            int ccx = Mth.floor(worldX(mx)) >> 4, ccz = Mth.floor(worldZ(my)) >> 4;
            int x0 = (int) Math.round(screenX(ccx * 16.0)), x1 = (int) Math.round(screenX(ccx * 16.0 + 16));
            int y0 = (int) Math.round(screenY(ccz * 16.0)), y1 = (int) Math.round(screenY(ccz * 16.0 + 16));
            x1 = Math.max(x1, x0 + 3);
            y1 = Math.max(y1, y0 + 3);
            g.fill(x0, y0, x1, y1, 0x10000000);
            outline(g, x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0x38FFFFFF); // faint light halo for dark areas
            outline(g, x0, y0, x1, y1, 0xE0100A04);
        }

        net.minecraft.world.entity.Entity hoverMob = null;
        // creatures (radar upgrade): small dots, or their heads while TAB is held (overlapping, nearer on top)
        boolean faces = Radar.portraitsHeld();
        // Screen.render() receives the frame time, not the partial tick: positions need the real partial tick,
        // otherwise creatures and players jitter while they move
        float ppt = mc.getTimer().getGameTimeDeltaPartialTick(true);
        java.util.List<Radar.Blip> blips = other ? List.of() : Radar.scan(mc, ppt); // creatures only in your world
        float hs = Ui.px(32);
        boolean drewHead = false;
        for (int i = blips.size() - 1; i >= 0; i--) { // farthest first, nearest last = on top
            Radar.Blip bl = blips.get(i);
            double sx = screenX(bl.x()), sy = screenY(bl.z());
            if (sx < mapX - 12 || sx > mapX + mapW + 12 || sy < mapY - 12 || sy > mapY + mapH + 12) continue;
            boolean face = faces && i < 150;
            if (face) {
                Radar.head(g, bl.e(), sx, sy, 4, bl.color(), bl.alpha());
                drewHead = true;
            } else {
                Radar.dot(g, sx, sy, 5, bl.color(), bl.alpha());
            }
            double hr = face ? hs / 2.0 : 3;
            if (mouseIn && Math.abs(mx - sx) <= hr && Math.abs(my - sy) <= hr) hoverMob = bl.e();
        }


        Vec3 me = mc.player.getPosition(ppt);
        Trail.draw(g, dim, (wx, wz) -> new double[]{screenX(wx), screenY(wz)}, me.x, me.z);

        for (Marker m : MarkerStore.MARKERS) {
            if (!m.dim.equals(dim)) continue;
            double sx = screenX(m.x + 0.5), sy = screenY(m.z + 0.5);
            if (sx < mapX - 14 || sx > mapX + mapW + 14 || sy < mapY - 14 || sy > mapY + mapH + 14) continue;
            Icons.draw(g, m.icon, (float) sx, (float) sy, 19f * is, m.color, 1f);
            if (mouseIn && Icons.contains(m.icon, 19f * is, mx - sx, my - sy)) hoverMarker = m;
        }

        for (AbstractClientPlayer p : mc.level.players()) {
            if (other || !(AtlasConfig.SHOW_PLAYERS.get() && ServerRules.showPlayers())) break;
            if (p == mc.player || p.isSpectator() || p.isInvisible()) continue;
            Vec3 pp = p.getPosition(ppt);
            double sx = screenX(pp.x), sy = screenY(pp.z);
            Ui.head(g, p, sx, sy, Ui.px(16) * headScale(ppb), false, 0f);
            if (mouseIn && hoverMarker == null && Math.abs(mx - sx) <= 7 && Math.abs(my - sy) <= 7) hoverPlayer = p;
        }
        // you; in another world: where you would be there (Nether x8)
        Ui.head(g, mc.player, screenX(me.x * k), screenY(me.z * k), Ui.px(16), true, mc.player.getViewYRot(ppt));
        renderSaved(g, dim);
        if (ruler) renderRuler(g, dim, mx, my, mouseIn);
        g.disableScissor();

        Ui.innerShadow(g, mapX, mapY, mapW, mapH);

        // north plate: odd width/height, centred on a real pixel, so the "N" sits exactly in the middle
        int nx = mapX + mapW / 2;
        g.fill(nx - 7, mapY - 6, nx + 8, mapY + 7, Ui.DARK);
        g.fill(nx - 6, mapY - 5, nx + 7, mapY + 6, Ui.BROWN);
        g.drawString(font, "N", nx - 2, mapY - 3, Ui.GOLD_LIGHT, false);

        if (sliderVisible()) renderSlider(g, mx, my);

        int sy0 = mapY + mapH + 9;
        String coords;
        String biome;
        if (mouseIn) {
            int bx = Mth.floor(worldX(mx)), bz = Mth.floor(worldZ(my));
            coords = bx + "   " + bz + "      " + I18n.get("screen.wayfarers_atlas.chunk") + " " + (bx >> 4) + ", " + (bz >> 4);
            biome = MapCache.biomeAt(key, bx, bz); // remembered with the map, so it works far away too
        } else if (other) {
            coords = "\u2248 " + Mth.floor(mc.player.getX() * k) + "   " + Mth.floor(mc.player.getZ() * k);
            biome = MapCache.biomeAt(key, Mth.floor(mc.player.getX() * k), Mth.floor(mc.player.getZ() * k));
        } else {
            coords = mc.player.getBlockX() + "   " + mc.player.getBlockY() + "   " + mc.player.getBlockZ();
            biome = BiomeNames.at(mc.level, mc.player.blockPosition());
        }
        g.drawString(font, coords, mapX, sy0, 0xFFD9C9A0, true);
        double lvl = LEVELS[levelIdx];
        String zoom = lvl >= 1.0 ? Math.round(lvl) + "x" : "1/" + Math.round(1.0 / lvl);
        g.drawString(font, zoom, mapX + mapW - font.width(zoom), sy0, 0xFFD9C9A0, true);

        // biome name: in the middle of the status line, or one line lower if there is not enough room
        String bname = BiomeNames.name(biome);
        if (!bname.isEmpty()) {
            int bw = font.width(bname);
            int bxs = mapX + (mapW - bw) / 2;
            boolean fits = bxs > mapX + font.width(coords) + 10 && bxs + bw < mapX + mapW - font.width(zoom) - 10;
            g.drawString(font, bname, bxs, fits ? sy0 : sy0 + 11, 0xFFC9DC9A, true);
        }

        if (menu != null) {
            renderMenu(g, mx, my);
        } else if (hoverMarker != null) {
            g.renderComponentTooltip(font, List.of(
                    Component.literal(hoverMarker.name).withStyle(ChatFormatting.BOLD),
                    Component.literal(hoverMarker.posText()).withStyle(ChatFormatting.GRAY)
            ), mx, my);
        } else if (hoverMob != null) {
            g.renderTooltip(font, hoverMob.getDisplayName(), mx, my);
        } else if (hoverPlayer != null) {
            g.renderTooltip(font, hoverPlayer.getDisplayName(), mx, my);
        }
    }

    // ------------------------------------------------------------------ context menu

    private void openMenu(int x, int y, List<Entry> entries) {
        menu = entries;
        int w = 0;
        for (Entry e : entries) w = Math.max(w, font.width(e.label()));
        menuW = w + 24;
        menuH = entries.size() * 18 + 8;
        menuX = Math.min(x, width - menuW - 4);
        menuY = Math.min(y, height - menuH - 4);
    }

    private void renderMenu(GuiGraphics g, int mx, int my) {
        Ui.roundRect(g, menuX + 1, menuY + 2, menuW, menuH, 6, 0x40000000);
        Ui.roundRect(g, menuX, menuY, menuW, menuH, 6, 0xCCB8934A);
        Ui.roundRect(g, menuX + 1, menuY + 1, menuW - 2, menuH - 2, 5, 0xF02B1D10);
        for (int i = 0; i < menu.size(); i++) {
            Entry e = menu.get(i);
            int ey = menuY + 4 + i * 18;
            boolean hot = e.enabled() && mx >= menuX + 3 && mx < menuX + menuW - 3 && my >= ey && my < ey + 18;
            if (hot) Ui.roundRect(g, menuX + 3, ey, menuW - 6, 18, 4, 0x55E8D29A);
            g.drawString(font, e.label(), menuX + 12, ey + 5, e.enabled() ? 0xFFF3E3B8 : 0xFF86775A, false);
        }
    }

    private Marker markerAt(double mx, double my) {
        String dim = shownDim();
        float size = 19f * MinimapLayer.iconScale(ppb) * AtlasConfig.MARKER_SIZE.get() / 100f;
        Marker best = null;
        double bestD = Double.MAX_VALUE;
        for (Marker m : MarkerStore.MARKERS) {
            if (!m.dim.equals(dim)) continue;
            double dx = mx - screenX(m.x + 0.5), dy = my - screenY(m.z + 0.5);
            if (!Icons.contains(m.icon, size, dx, dy)) continue;
            double d = dx * dx + dy * dy;
            if (d < bestD) {
                best = m;
                bestD = d;
            }
        }
        return best;
    }

    private List<Entry> markerMenu(Marker m) {
        List<Entry> list = new ArrayList<>();
        if (!m.death) { // a death mark is not edited
            list.add(new Entry(Component.translatable("menu.wayfarers_atlas.edit"), true,
                    () -> minecraft.setScreen(new MarkerScreen(this, m, false))));
        }
        boolean tpOk = canTeleport() && m.dim.equals(MapScanner.dimId(minecraft.level));
        if ((AtlasConfig.TELEPORT.get() && ServerRules.teleport())) {
            list.add(new Entry(Component.translatable(canTeleport() ? "menu.wayfarers_atlas.tp_marker" : "menu.wayfarers_atlas.tp_denied"), tpOk, () -> {
                TeleportHelper.go(m.x, m.y, m.z);
                onClose();
            }));
        }
        list.add(new Entry(Component.translatable("menu.wayfarers_atlas.share_chat"), true, () -> {
            minecraft.player.connection.sendChat(ShareUtil.encode(m));
            onClose();
        }));
        list.add(new Entry(Component.translatable("menu.wayfarers_atlas.delete"), true, () -> {
            MarkerStore.MARKERS.remove(m);
            MarkerStore.save();
        }));
        return list;
    }

    private List<Entry> groundMenu(double mx, double my) {
        // the block is fixed at the moment of the click, so the marker lands exactly where you pointed
        int bx = Mth.floor(worldX(mx)), bz = Mth.floor(worldZ(my));
        List<Entry> list = new ArrayList<>();
        list.add(new Entry(Component.translatable("menu.wayfarers_atlas.new_marker"), true, () -> {
            int y = otherDim() ? Marker.NO_Y : TeleportHelper.pointY(minecraft.level, bx, bz); // cave floor / surface
            Marker m = new Marker(I18n.get("marker.wayfarers_atlas.default"), shownDim(),
                    bx, y, bz, MarkerScreen.PALETTE[0], 0);
            minecraft.setScreen(new MarkerScreen(this, m, true));
        }));
        boolean can = canTeleport();
        if (AtlasConfig.TELEPORT.get() && ServerRules.teleport() && !otherDim()) { // only inside the world you are in
            list.add(new Entry(Component.translatable(can ? "menu.wayfarers_atlas.tp_here" : "menu.wayfarers_atlas.tp_denied"), can, () -> {
                TeleportHelper.go(bx, Marker.NO_Y, bz);
                onClose();
            }));
        }
        list.add(new Entry(Component.translatable("menu.wayfarers_atlas.copy_coords"), true, () -> {
            int y = otherDim() ? Marker.NO_Y : TeleportHelper.pointY(minecraft.level, bx, bz);
            minecraft.keyboardHandler.setClipboard(bx + " " + (y == Marker.NO_Y ? "~" : Integer.toString(y)) + " " + bz);
        }));
        return list;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (menu != null) {
            boolean inside = mx >= menuX && mx < menuX + menuW && my >= menuY && my < menuY + menuH;
            if (inside && button == 0) {
                int i = (int) ((my - menuY - 4) / 18);
                if (i >= 0 && i < menu.size() && menu.get(i).enabled()) {
                    Entry e = menu.get(i);
                    menu = null;
                    e.action().run();
                }
                return true;
            }
            menu = null;
            if (button != 1) return true; // a click elsewhere just closes the menu
        }
        if (button == 0 && onSlider(mx, my)) {
            draggingSlider = true;
            sliderSet(my);
            return true;
        }
        if (ruler && inMap(mx, my)) {
            if (button == 0) { // a point is added on release, if the map was not dragged
                pressX = mx;
                pressY = my;
                pressing = true;
                return true;
            }
            if (button == 1 && !RULER.isEmpty()) { // right click: take the last point back (rulers left on the map: menu below)
                RULER.remove(RULER.size() - 1);
                return true;
            }
        }
        if (button == 1 && inMap(mx, my)) {
            MarkerStore.Ruler saved = rulerAt(mx, my);
            if (saved != null) {
                openMenu((int) mx, (int) my, List.of(new Entry(Component.translatable("menu.wayfarers_atlas.remove_ruler"), true,
                        () -> {
                            MarkerStore.RULERS.remove(saved);
                            MarkerStore.save();
                        })));
                return true;
            }
            Marker existing = markerAt(mx, my);
            openMenu((int) mx, (int) my, existing != null ? markerMenu(existing) : groundMenu(mx, my));
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        draggingSlider = false;
        if (ruler && pressing && button == 0) {
            pressing = false;
            if (Math.hypot(mx - pressX, my - pressY) < 4 && inMap(mx, my)) {
                RULER.add(new double[]{Math.floor(worldX(mx)) + 0.5, Math.floor(worldZ(my)) + 0.5});
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.6f, 0.25f));
            }
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (draggingSlider) {
            sliderSet(my);
            return true;
        }
        if (menu == null && button == 0 && inMap(mx, my)) {
            if (zoomAnim) {
                // zooming while holding the map: the point that stays fixed moves with the mouse,
                // so the zoom keeps gliding smoothly and the map still follows the drag
                zsx += dx;
                zsy += dy;
            } else {
                cx -= dx * bpp();
                cz -= dy * bpp();
                tx = cx;
                tz = cz;
            }
            animating = false;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (scrollY == 0) return false;
        if (onSlider(mx, my)) {
            int lo = minecraft.level.getMinBuildHeight(), hi = minecraft.level.getMaxBuildHeight() - 1;
            MapScanner.setManualY(Mth.clamp(MapScanner.manualY() + (scrollY > 0 ? 2 : -2), lo, hi));
            return true;
        }
        if (!inMap(mx, my)) return false;
        menu = null;
        zoomStep(scrollY > 0 ? 1 : -1, mx, my);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (menu != null && key == GLFW.GLFW_KEY_ESCAPE) {
            menu = null;
            return true;
        }
        if (ruler && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) { // leave this ruler on the map
            commitRuler();
            return true;
        }
        if (ruler && key == GLFW.GLFW_KEY_ESCAPE) { // Esc: clear the points, then switch the ruler off
            if (!RULER.isEmpty()) RULER.clear();
            else toggleRuler();
            return true;
        }
        if (key == GLFW.GLFW_KEY_R && !(getFocused() instanceof net.minecraft.client.gui.components.EditBox)) {
            toggleRuler();
            return true;
        }
        if (Keys.OPEN_MAP.matches(key, scan)) {
            if (!holdGuard) onClose();
            return true;
        }
        if (key == GLFW.GLFW_KEY_SPACE) {
            centerOnPlayer(true);
            return true;
        }
        if (sliderVisible() && (key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_UP)) {
            nudgeY(hasShiftDown() ? 8 : 1);
            return true;
        }
        if (sliderVisible() && (key == GLFW.GLFW_KEY_PAGE_DOWN || key == GLFW.GLFW_KEY_DOWN)) {
            nudgeY(hasShiftDown() ? -8 : -1);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }
}

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

    private List<Entry> menu = null;
    private int menuX, menuY, menuW, menuH;

    public MapScreen() {
        super(Component.translatable("screen.wayfarers_atlas.map"));
        holdGuard = Keys.physicallyDown(Keys.OPEN_MAP);
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
        if (smooth) {
            tx = minecraft.player.getX();
            tz = minecraft.player.getZ();
            animating = true;
        } else {
            cx = tx = minecraft.player.getX();
            cz = tz = minecraft.player.getZ();
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

    private static Component dimName(String dim) {
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
        String dim = MapScanner.dimId(mc.level);
        String key = MapScanner.viewKey(mc.level);
        double bpp = bpp();
        float is = MinimapLayer.iconScale(ppb) * AtlasConfig.MARKER_SIZE.get() / 100f;

        g.drawString(font, Component.translatable("screen.wayfarers_atlas.map").withStyle(ChatFormatting.BOLD), 28, 10, Ui.GOLD_LIGHT, true);
        Component sub = MapScanner.isCave()
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
        java.util.List<Radar.Blip> blips = Radar.scan(mc, ppt);
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


        for (Marker m : MarkerStore.MARKERS) {
            if (!m.dim.equals(dim)) continue;
            double sx = screenX(m.x + 0.5), sy = screenY(m.z + 0.5);
            if (sx < mapX - 14 || sx > mapX + mapW + 14 || sy < mapY - 14 || sy > mapY + mapH + 14) continue;
            Icons.draw(g, m.icon, (float) sx, (float) sy, 22f * is, m.color, 1f);
            if (mouseIn && Icons.contains(m.icon, 22f * is, mx - sx, my - sy)) hoverMarker = m;
        }

        for (AbstractClientPlayer p : mc.level.players()) {
            if (!AtlasConfig.SHOW_PLAYERS.get()) break;
            if (p == mc.player || p.isSpectator() || p.isInvisible()) continue;
            Vec3 pp = p.getPosition(ppt);
            double sx = screenX(pp.x), sy = screenY(pp.z);
            Ui.head(g, p, sx, sy, Ui.px(16) * headScale(ppb), false, 0f);
            if (mouseIn && hoverMarker == null && Math.abs(mx - sx) <= 7 && Math.abs(my - sy) <= 7) hoverPlayer = p;
        }
        Vec3 me = mc.player.getPosition(ppt);
        Ui.head(g, mc.player, screenX(me.x), screenY(me.z), Ui.px(16), true, mc.player.getViewYRot(ppt));
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
        String dim = MapScanner.dimId(minecraft.level);
        float size = 22f * MinimapLayer.iconScale(ppb) * AtlasConfig.MARKER_SIZE.get() / 100f;
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
        list.add(new Entry(Component.translatable("menu.wayfarers_atlas.edit"), true,
                () -> minecraft.setScreen(new MarkerScreen(this, m, false))));
        boolean tpOk = canTeleport() && m.dim.equals(MapScanner.dimId(minecraft.level));
        if (AtlasConfig.TELEPORT.get()) {
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
            int y = TeleportHelper.pointY(minecraft.level, bx, bz); // cave floor in cave view, surface otherwise
            Marker m = new Marker(I18n.get("marker.wayfarers_atlas.default"), MapScanner.dimId(minecraft.level),
                    bx, y, bz, MarkerScreen.PALETTE[0], 0);
            minecraft.setScreen(new MarkerScreen(this, m, true));
        }));
        boolean can = canTeleport();
        if (AtlasConfig.TELEPORT.get()) {
            list.add(new Entry(Component.translatable(can ? "menu.wayfarers_atlas.tp_here" : "menu.wayfarers_atlas.tp_denied"), can, () -> {
                TeleportHelper.go(bx, Marker.NO_Y, bz);
                onClose();
            }));
        }
        list.add(new Entry(Component.translatable("menu.wayfarers_atlas.copy_coords"), true, () -> {
            int y = TeleportHelper.pointY(minecraft.level, bx, bz);
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
        if (button == 1 && inMap(mx, my)) {
            Marker existing = markerAt(mx, my);
            openMenu((int) mx, (int) my, existing != null ? markerMenu(existing) : groundMenu(mx, my));
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        draggingSlider = false;
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

package com.wayfarersatlas;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Everything written into the Atlas in one list: markers, regions and rulers, with a search box. Click a line to see
 * it on the map; right-click for its menu. Things in the world you are in come first, nearest first.
 */
public class MarkerListScreen extends Screen {
    private static final int ROW = 24;
    private static String lastSearch = "";
    private static String filterDim = null; // only markers of this world (null = all worlds)
    private static int sort = 0; // 0 = nearest first, 1 = by name, 2 = by kind (markers, regions, rulers)
    private static final String[] SORT_KEYS = {"screen.wayfarers_atlas.sort.distance", "screen.wayfarers_atlas.sort.name",
            "screen.wayfarers_atlas.sort.kind"};

    private final MapScreen parent;
    private EditBox search;
    private int px, py, pw, ph, listY, listH;
    private double scroll = 0, scrollTarget = 0;
    private List<Object> shown = new ArrayList<>(); // Marker, MarkerStore.Region or MarkerStore.Ruler

    private record Entry(Component label, boolean enabled, Runnable action) {}

    private List<Entry> menu = null;
    private int menuX, menuY, menuW, menuH;

    public MarkerListScreen(MapScreen parent) {
        super(Component.translatable("screen.wayfarers_atlas.markers"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        pw = Math.min(340, width - 20);
        ph = Math.min(height - 20, 300);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        // world filter on the right of the search box
        Component fl = (filterDim == null ? Component.translatable("screen.wayfarers_atlas.all_worlds")
                : MapScreen.dimName(filterDim)).copy().append(" \u25be");
        int fw = Math.min(130, Math.max(80, font.width(fl) + 16));
        addRenderableWidget(new ParchButton(px + pw - 14 - fw, py + 26, fw, 20, fl, ParchButton.Kind.TEXT, false,
                b -> openWorldFilter(b.getX(), b.getY() + b.getHeight() + 2)));
        search = new EditBox(font, px + 14, py + 28, pw - 28 - fw - 6, 16, Component.translatable("screen.wayfarers_atlas.search"));
        search.setHint(Component.translatable("screen.wayfarers_atlas.search").withStyle(s -> s.withColor(0xFF8A7556)));
        search.setValue(lastSearch);
        search.setResponder(s -> {
            lastSearch = s;
            scrollTarget = 0;
            refresh();
        });
        addRenderableWidget(search);
        setInitialFocus(search);
        listY = py + 52;
        listH = ph - 52 - 34;
        addRenderableWidget(new ParchButton(px + 14, py + ph - 28, 110, 20,
                Component.translatable("screen.wayfarers_atlas.import").copy().append(" \u25be"), ParchButton.Kind.TEXT, false,
                b -> openImport(b.getX(), b.getY())));
        Component sl = Component.translatable(SORT_KEYS[sort]).copy().append(" \u25be");
        int sw = Math.max(90, font.width(sl) + 16);
        addRenderableWidget(new ParchButton(px + 14 + 110 + 6, py + ph - 28, sw, 20, sl, ParchButton.Kind.TEXT, false,
                b -> openSort(b.getX(), b.getY())));
        addRenderableWidget(new ParchButton(px + pw - 14 - 100, py + ph - 28, 100, 20,
                Component.translatable("screen.wayfarers_atlas.close"), ParchButton.Kind.TEXT, false, b -> onClose()));
        refresh();
    }

    private String dimOf() {
        return minecraft.level == null ? "" : MapScanner.dimId(minecraft.level);
    }

    // ---- the three kinds of entries

    private static String dimOf(Object o) {
        if (o instanceof Marker m) return m.dim;
        if (o instanceof MarkerStore.Region r) return r.dim;
        return ((MarkerStore.Ruler) o).dim;
    }

    /** Where it is: the marker, the middle of a region, the start of a ruler. */
    private static double[] posOf(Object o) {
        if (o instanceof Marker m) return new double[]{m.x + 0.5, m.z + 0.5};
        List<int[]> pts = o instanceof MarkerStore.Region r ? r.points : ((MarkerStore.Ruler) o).points;
        if (o instanceof MarkerStore.Ruler) return new double[]{pts.get(0)[0] + 0.5, pts.get(0)[1] + 0.5};
        double x = 0, z = 0;
        for (int[] p : pts) {
            x += p[0] + 0.5;
            z += p[1] + 0.5;
        }
        return new double[]{x / pts.size(), z / pts.size()};
    }

    private static int rulerLength(MarkerStore.Ruler r) {
        double t = 0;
        for (int i = 1; i < r.points.size(); i++) {
            int[] a = r.points.get(i - 1), b = r.points.get(i);
            t += Math.round(Math.hypot(b[0] - a[0], b[1] - a[1]));
        }
        return (int) t;
    }

    private String nameOf(Object o) {
        if (o instanceof Marker m) return m.name;
        if (o instanceof MarkerStore.Region r) return r.name;
        return Component.translatable("screen.wayfarers_atlas.ruler_entry", rulerLength((MarkerStore.Ruler) o)).getString();
    }

    private double dist(Object o) {
        if (minecraft.player == null || !dimOf(o).equals(dimOf())) return Double.MAX_VALUE;
        double[] p = posOf(o);
        return Math.hypot(p[0] - minecraft.player.getX(), p[1] - minecraft.player.getZ());
    }

    private void refresh() {
        String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        String here = dimOf();
        List<Object> all = new ArrayList<>(MarkerStore.MARKERS);
        all.addAll(MarkerStore.REGIONS);
        all.addAll(MarkerStore.RULERS);
        List<Object> list = new ArrayList<>();
        for (Object o : all) {
            String d = dimOf(o);
            if (filterDim != null && !d.equals(filterDim)) continue;
            if (q.isEmpty() || nameOf(o).toLowerCase(Locale.ROOT).contains(q)
                    || MapScreen.dimName(d).getString().toLowerCase(Locale.ROOT).contains(q)) list.add(o);
        }
        Comparator<Object> byName = Comparator.comparing(o -> nameOf(o).toLowerCase(Locale.ROOT));
        Comparator<Object> byDist = Comparator.<Object>comparingInt(o -> dimOf(o).equals(here) ? 0 : 1)
                .thenComparing(MarkerListScreen::dimOf)
                .thenComparingDouble(this::dist);
        Comparator<Object> byKind = Comparator.comparingInt(o -> o instanceof Marker ? 0 : o instanceof MarkerStore.Region ? 1 : 2);
        list.sort(switch (sort) {
            case 1 -> byName.thenComparing(byDist);
            case 2 -> byKind.thenComparing(byDist).thenComparing(byName);
            default -> byDist.thenComparing(byName);
        });
        shown = list;
        clampScroll();
    }

    private int maxScroll() {
        return Math.max(0, shown.size() * ROW - listH);
    }

    private void clampScroll() {
        scrollTarget = Mth.clamp(scrollTarget, 0, maxScroll());
        scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    private int rowAt(double mx, double my) {
        if (mx < px + 8 || mx > px + pw - 8 || my < listY || my >= listY + listH) return -1;
        int i = (int) ((my - listY + scroll) / ROW);
        return i >= 0 && i < shown.size() ? i : -1;
    }

    // ------------------------------------------------------------------ rendering

    /** The map stays visible behind the list. */
    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        scroll += (scrollTarget - scroll) * 0.35;
        parent.render(g, -1000, -1000, pt);
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        g.fill(0, 0, width, height, 0x88000000);
        Ui.panel(g, px, py, pw, ph);
        Ui.fitCentered(g, font, title.getString(), px + pw / 2f, py + 12, pw - 20, Ui.INK, false);

        g.enableScissor(px + 6, listY, px + pw - 6, listY + listH);
        int hover = menu == null ? rowAt(mx, my) : -1;
        String here = dimOf();
        String b = net.minecraft.client.resources.language.I18n.get("screen.wayfarers_atlas.blocks");
        for (int i = 0; i < shown.size(); i++) {
            int y = listY + i * ROW - (int) Math.round(scroll);
            if (y + ROW < listY || y > listY + listH) continue;
            Object o = shown.get(i);
            if (i == hover) Ui.roundRect(g, px + 8, y + 1, pw - 16, ROW - 2, 4, 0x33FFFFFF);
            int cy = y + ROW / 2;
            if (o instanceof Marker mk) {
                Icons.drawCentered(g, mk.icon, px + 22, cy, 16f, mk.color, mk.hidden ? 0.35f : 1f); // hidden: faint
            } else if (o instanceof MarkerStore.Region rg) { // a little patch in the region's colour
                Ui.roundRect(g, px + 15, cy - 6, 14, 12, 3, 0xFF3A2410);
                Ui.roundRect(g, px + 16, cy - 5, 12, 10, 2, 0xFF000000 | rg.color);
            } else { // a little ruler
                g.fill(px + 15, cy - 2, px + 29, cy + 2, 0xFF3A2410);
                g.fill(px + 16, cy - 1, px + 28, cy + 1, 0xFFFFD86A);
                for (int t = 17; t < 28; t += 3) g.fill(px + t, cy - 4, px + t + 1, cy - 2, 0xFF3A2410);
            }
            g.drawString(font, nameOf(o), px + 36, y + 3, Ui.INK, false);
            double[] pos = posOf(o);
            String sub = (o instanceof Marker mk ? mk.posText() : (int) Math.floor(pos[0]) + ", " + (int) Math.floor(pos[1]))
                    + "  ·  " + MapScreen.dimName(dimOf(o)).getString();
            g.pose().pushPose();
            g.pose().translate(px + 36, y + 13, 0);
            g.pose().scale(0.75f, 0.75f, 1f);
            g.drawString(font, sub, 0, 0, 0xFF7A6040, false);
            g.pose().popPose();
            if (dimOf(o).equals(here)) {
                String d = (int) Math.round(dist(o)) + " " + b;
                g.drawString(font, d, px + pw - 14 - font.width(d), y + 8, 0xFF5A3F22, false);
            }
        }
        if (shown.isEmpty()) {
            boolean none = MarkerStore.MARKERS.isEmpty() && MarkerStore.REGIONS.isEmpty() && MarkerStore.RULERS.isEmpty();
            String key = none ? "screen.wayfarers_atlas.no_markers" : "screen.wayfarers_atlas.nothing_found";
            Ui.fitCentered(g, font, Component.translatable(key).getString(), px + pw / 2f, listY + 20, pw - 30, 0xFF7A6040, false);
        }
        g.disableScissor();
        if (maxScroll() > 0) { // scroll bar
            int barH = Math.max(16, listH * listH / (shown.size() * ROW));
            int barY = listY + (int) ((listH - barH) * (scroll / maxScroll()));
            g.fill(px + pw - 9, listY, px + pw - 7, listY + listH, 0x223A2410);
            g.fill(px + pw - 9, barY, px + pw - 7, barY + barH, 0xAA5A3F22);
        }
        g.pose().popPose();

        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        super.render(g, mx, my, pt); // search box and button
        if (menu != null) renderMenu(g, mx, my);
        g.pose().popPose();
    }

    private void renderMenu(GuiGraphics g, int mx, int my) {
        g.flush();
        g.pose().pushPose();
        g.pose().translate(0, 0, 500); // above item pictures on the map (they are drawn high up)
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
        g.pose().popPose();
    }

    // ------------------------------------------------------------------ actions

    private boolean canTeleport(Marker m) {
        return AtlasConfig.TELEPORT.get() && ServerRules.teleport() && minecraft.player != null
                && minecraft.player.hasPermissions(2) && m.dim.equals(dimOf());
    }

    /** World filter: all worlds, or one world that has markers. */
    private void openWorldFilter(int x, int y) {
        List<Entry> list = new ArrayList<>();
        list.add(new Entry(Component.literal(filterDim == null ? "\u2022 " : "   ")
                .append(Component.translatable("screen.wayfarers_atlas.all_worlds")), true, () -> setFilter(null)));
        java.util.TreeSet<String> dims = new java.util.TreeSet<>();
        for (Marker m : MarkerStore.MARKERS) dims.add(m.dim);
        for (MarkerStore.Region r : MarkerStore.REGIONS) dims.add(r.dim);
        for (MarkerStore.Ruler r : MarkerStore.RULERS) dims.add(r.dim);
        dims.add(dimOf());
        for (String d : dims) {
            list.add(new Entry(Component.literal(d.equals(filterDim) ? "\u2022 " : "   ").append(MapScreen.dimName(d)), true,
                    () -> setFilter(d)));
        }
        showMenu(x, y, list);
    }

    /** Markers from Xaero's Minimap: pick the world folder to take them from. */
    private void openImport(int x, int y) {
        List<Entry> list = new ArrayList<>();
        for (WaypointImport.Source s : WaypointImport.find()) {
            list.add(new Entry(Component.literal(s.label()), true, () -> {
                int n = WaypointImport.importFrom(s);
                refresh();
                minecraft.player.displayClientMessage(Component.translatable("msg.wayfarers_atlas.imported", n, s.mod()), true);
                minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 1.5f, 0.6f));
            }));
        }
        if (list.isEmpty()) list.add(new Entry(Component.translatable("screen.wayfarers_atlas.import_none"), false, () -> {}));
        showMenu(x, y, list);
        menuY = Math.max(4, y - menuH - 2); // above the button
    }

    /** How the list is sorted: nearest first, by name, by kind. */
    private void openSort(int x, int y) {
        List<Entry> list = new ArrayList<>();
        for (int i = 0; i < SORT_KEYS.length; i++) {
            final int k = i;
            list.add(new Entry(Component.literal(i == sort ? "\u2022 " : "   ").append(Component.translatable(SORT_KEYS[i])), true, () -> {
                sort = k;
                rebuildWidgets();
            }));
        }
        showMenu(x, y, list);
        menuY = Math.max(4, y - menuH - 2); // above the button
    }

    private void setFilter(String d) {
        filterDim = d;
        scrollTarget = 0;
        rebuildWidgets(); // the button shows the chosen world
    }

    private void showMenu(int x, int y, List<Entry> list) {
        menu = list;
        int w = 0;
        for (Entry e : list) w = Math.max(w, font.width(e.label()));
        menuW = w + 24;
        menuH = list.size() * 18 + 8;
        menuX = Math.min(x, width - menuW - 4);
        menuY = Math.min(y, height - menuH - 4);
    }

    private void openMenu(int x, int y, Marker m) {
        List<Entry> list = new ArrayList<>();
        list.add(new Entry(Component.translatable("menu.wayfarers_atlas.show_on_map"), true, () -> show(m)));
        list.add(Nav.is(m)
                ? new Entry(Component.translatable("menu.wayfarers_atlas.stop_lead"), true, Nav::clear)
                : new Entry(Component.translatable("menu.wayfarers_atlas.lead"), true, () -> {
                    Nav.set(m);
                    minecraft.setScreen(null);
                }));
        if (!m.death) {
            list.add(new Entry(Component.translatable("menu.wayfarers_atlas.edit"), true,
                    () -> minecraft.setScreen(new MarkerScreen(this, m, false))));
        }
        if (AtlasConfig.TELEPORT.get() && ServerRules.teleport()) {
            list.add(new Entry(Component.translatable("menu.wayfarers_atlas.tp_marker"), canTeleport(m), () -> {
                TeleportHelper.go(m.x, m.y, m.z);
                minecraft.setScreen(null);
            }));
        }
        list.add(new Entry(Component.translatable(m.hidden ? "menu.wayfarers_atlas.unhide_marker" : "menu.wayfarers_atlas.hide_marker"),
                true, () -> {
                    m.hidden = !m.hidden;
                    if (m.hidden && Nav.is(m)) Nav.clear();
                    MarkerStore.save();
                }));
        list.add(new Entry(Component.translatable("menu.wayfarers_atlas.share_chat"), true, () -> {
            minecraft.player.connection.sendChat(ShareUtil.encode(m));
            minecraft.setScreen(null);
        }));
        list.add(new Entry(Component.translatable("menu.wayfarers_atlas.delete"), true, () -> {
            MarkerStore.MARKERS.remove(m);
            MarkerStore.save();
            refresh();
        }));
        showMenu(x, y, list);
    }

    /** Menu of a region or a ruler: show it, (region) edit, delete. */
    private void openMenu(int x, int y, Object o) {
        if (o instanceof Marker m) {
            openMenu(x, y, m);
            return;
        }
        List<Entry> list = new ArrayList<>();
        list.add(new Entry(Component.translatable("menu.wayfarers_atlas.show_on_map"), true, () -> show(o)));
        if (o instanceof MarkerStore.Region r) {
            list.add(new Entry(Component.translatable("menu.wayfarers_atlas.edit"), true,
                    () -> minecraft.setScreen(new RegionScreen(this, r, false))));
        }
        list.add(new Entry(Component.translatable("menu.wayfarers_atlas.delete"), true, () -> {
            if (o instanceof MarkerStore.Region r) MarkerStore.REGIONS.remove(r);
            else MarkerStore.RULERS.remove((MarkerStore.Ruler) o);
            MarkerStore.save();
            refresh();
        }));
        showMenu(x, y, list);
    }

    /** Back to the map, centred on it (in its world). */
    private void show(Object o) {
        if (o instanceof Marker m) parent.focusOn(m);
        else {
            double[] p = posOf(o);
            parent.focusAt(dimOf(o), p[0], p[1]);
        }
        minecraft.setScreen(parent);
    }

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
            return true;
        }
        int row = rowAt(mx, my);
        if (row >= 0) {
            Object o = shown.get(row);
            if (button == 0) show(o);
            else if (button == 1) openMenu((int) mx, (int) my, o);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scrollTarget = Mth.clamp(scrollTarget - sy * ROW * 2, 0, maxScroll());
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (menu != null && key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            menu = null;
            return true;
        }
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER && !shown.isEmpty()) { // Enter: the first one found
            show(shown.get(0));
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

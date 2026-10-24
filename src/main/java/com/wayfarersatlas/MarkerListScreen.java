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
 * All markers of the Atlas in one list, with a search box. Click a marker to see it on the map; right-click for
 * edit / teleport / share / delete. Markers of the world you are in come first, nearest first.
 */
public class MarkerListScreen extends Screen {
    private static final int ROW = 24;
    private static String lastSearch = "";
    private static String filterDim = null; // only markers of this world (null = all worlds)

    private final MapScreen parent;
    private EditBox search;
    private int px, py, pw, ph, listY, listH;
    private double scroll = 0, scrollTarget = 0;
    private List<Marker> shown = new ArrayList<>();

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
        addRenderableWidget(new ParchButton(px + pw - 14 - 100, py + ph - 28, 100, 20,
                Component.translatable("screen.wayfarers_atlas.close"), ParchButton.Kind.TEXT, false, b -> onClose()));
        refresh();
    }

    private String dimOf() {
        return minecraft.level == null ? "" : MapScanner.dimId(minecraft.level);
    }

    private double dist(Marker m) {
        if (minecraft.player == null || !m.dim.equals(dimOf())) return Double.MAX_VALUE;
        return Math.hypot(m.x + 0.5 - minecraft.player.getX(), m.z + 0.5 - minecraft.player.getZ());
    }

    private void refresh() {
        String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        String here = dimOf();
        List<Marker> list = new ArrayList<>();
        for (Marker m : MarkerStore.MARKERS) {
            if (filterDim != null && !m.dim.equals(filterDim)) continue;
            if (q.isEmpty() || m.name.toLowerCase(Locale.ROOT).contains(q)
                    || MapScreen.dimName(m.dim).getString().toLowerCase(Locale.ROOT).contains(q)) list.add(m);
        }
        list.sort(Comparator.<Marker>comparingInt(m -> m.dim.equals(here) ? 0 : 1)
                .thenComparing(m -> m.dim)
                .thenComparingDouble(this::dist)
                .thenComparing(m -> m.name.toLowerCase(Locale.ROOT)));
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
            Marker m = shown.get(i);
            if (i == hover) Ui.roundRect(g, px + 8, y + 1, pw - 16, ROW - 2, 4, 0x33FFFFFF);
            Icons.drawCentered(g, m.icon, px + 22, y + ROW / 2f, 16f, m.color, 1f);
            g.drawString(font, m.name, px + 36, y + 3, Ui.INK, false);
            String sub = m.posText() + "  ·  " + MapScreen.dimName(m.dim).getString();
            g.pose().pushPose();
            g.pose().translate(px + 36, y + 13, 0);
            g.pose().scale(0.75f, 0.75f, 1f);
            g.drawString(font, sub, 0, 0, 0xFF7A6040, false);
            g.pose().popPose();
            if (m.dim.equals(here)) {
                String d = (int) Math.round(dist(m)) + " " + b;
                g.drawString(font, d, px + pw - 14 - font.width(d), y + 8, 0xFF5A3F22, false);
            }
        }
        if (shown.isEmpty()) {
            String key = MarkerStore.MARKERS.isEmpty() ? "screen.wayfarers_atlas.no_markers" : "screen.wayfarers_atlas.nothing_found";
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

    /** Back to the map, centred on the marker (in its world). */
    private void show(Marker m) {
        parent.focusOn(m);
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
            Marker m = shown.get(row);
            if (button == 0) show(m);
            else if (button == 1) openMenu((int) mx, (int) my, m);
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

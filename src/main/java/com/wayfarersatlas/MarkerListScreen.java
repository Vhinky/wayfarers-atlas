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
 * it on the map; right-click for its menu. Your markers, regions and rulers can be put into folders (drag one onto
 * a folder, or its menu); a folder can be folded, renamed, and hidden from the maps with its eye.
 */
public class MarkerListScreen extends Screen {
    private static final int ROW = 24;
    private static String lastSearch = "";
    private static String filterDim = null; // only markers of this world (null = all worlds)
    private static int sort = 3; // 0 = nearest first, 1 = by name, 2 = by kind (markers, regions, rulers), 3 = in folders
    private static boolean looseClosed = false; // the "no folder" group is folded
    private static boolean reverse = false; // the other way round
    private static int kindFilter = 0;      // 0 = everything, 1 = markers, 2 = regions, 3 = rulers
    private static final String[] KIND_KEYS = {"screen.wayfarers_atlas.kind.all", "screen.wayfarers_atlas.kind.markers",
            "screen.wayfarers_atlas.kind.regions", "screen.wayfarers_atlas.kind.rulers"};

    /** A title line between groups (a world, or a kind of entry). */
    private record Header(String text) {}
    private static final String[] SORT_KEYS = {"screen.wayfarers_atlas.sort.distance", "screen.wayfarers_atlas.sort.name",
            "screen.wayfarers_atlas.sort.kind", "screen.wayfarers_atlas.sort.folders"};

    /** A folder line (folder null: the things in no folder), with the number of things listed in it. */
    private record FolderRow(MarkerStore.Folder folder, int count) {}

    // a marker, region or ruler being dragged onto a folder
    private int pressRow = -1;
    private double pressX, pressY;
    private Object dragged = null;

    // asking for a name (new folder, rename)
    private EditBox prompt = null;
    private String promptTitle = "";
    private java.util.function.Consumer<String> promptDone = null;

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
        int fw = Math.min(110, Math.max(70, font.width(fl) + 16));
        addRenderableWidget(new ParchButton(px + pw - 14 - fw, py + 26, fw, 20, fl, ParchButton.Kind.TEXT, false,
                b -> openWorldFilter(b.getX(), b.getY() + b.getHeight() + 2)));
        // kind filter (everything / markers / regions / rulers) left of it
        Component kl = Component.translatable(KIND_KEYS[kindFilter]).copy().append(" \u25be");
        int kw = Math.min(100, Math.max(60, font.width(kl) + 16));
        addRenderableWidget(new ParchButton(px + pw - 14 - fw - 4 - kw, py + 26, kw, 20, kl, ParchButton.Kind.TEXT, false,
                b -> openKindFilter(b.getX(), b.getY() + b.getHeight() + 2)));
        search = new EditBox(font, px + 14, py + 28, pw - 28 - fw - 4 - kw - 6, 16, Component.translatable("screen.wayfarers_atlas.search"));
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
        // bottom row: Import on the left, Close on the right, sorting and the reverse arrow in between (they
        // shrink if there is little room, so nothing hides under another button)
        Component il = Component.translatable("screen.wayfarers_atlas.import").copy().append(" \u25be");
        int iw = Math.min(110, font.width(il) + 16), cw = Math.min(100, font.width(Component.translatable("screen.wayfarers_atlas.close")) + 24);
        int by = py + ph - 28, closeX = px + pw - 14 - cw, sortX = px + 14 + iw + 4;
        addRenderableWidget(new ParchButton(px + 14, by, iw, 20, il, ParchButton.Kind.TEXT, false,
                b -> openImport(b.getX(), b.getY())));
        Component nl = Component.literal("+ ").append(Component.translatable("screen.wayfarers_atlas.folder"));
        int nw = font.width(nl) + 14;
        int rowEnd = closeX - nw - 4; // the new-folder button sits left of Close
        addRenderableWidget(new ParchButton(rowEnd, by, nw, 20, nl, ParchButton.Kind.TEXT, false, b -> newFolder(null)));
        Component sl = Component.translatable(SORT_KEYS[sort]).copy().append(" \u25be");
        int sw = Math.max(40, Math.min(font.width(sl) + 16, rowEnd - 4 - 20 - 4 - sortX));
        addRenderableWidget(new ParchButton(sortX, by, sw, 20, sl, ParchButton.Kind.TEXT, false,
                b -> openSort(b.getX(), b.getY())));
        addRenderableWidget(new ParchButton(sortX + sw + 4, by, 20, 20,
                Component.literal(reverse ? "\u2191" : "\u2193"), ParchButton.Kind.TEXT, reverse, b -> { // reverse the order
            reverse = !reverse;
            rebuildWidgets();
        }));
        addRenderableWidget(new ParchButton(closeX, by, cw, 20,
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
        List<Object> all = new ArrayList<>(MarkerStore.allMarkers()); // the server's markers and regions are listed too
        all.addAll(MarkerStore.allRegions());
        all.addAll(MarkerStore.RULERS);
        List<Object> list = new ArrayList<>();
        for (Object o : all) {
            if (kindFilter == 1 && !(o instanceof Marker) || kindFilter == 2 && !(o instanceof MarkerStore.Region)
                    || kindFilter == 3 && !(o instanceof MarkerStore.Ruler)) continue;
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
        if (reverse) java.util.Collections.reverse(list);
        if (sort == 3) { // in folders: each folder (folded or not), then everything in no folder
            List<Object> out = new ArrayList<>();
            for (MarkerStore.Folder f : MarkerStore.FOLDERS) {
                List<Object> in = new ArrayList<>();
                for (Object o : list) if (own(o) && f.name.equals(MarkerStore.folderOf(o))) in.add(o);
                if (!q.isEmpty() && in.isEmpty()) continue; // searching: only folders with something found
                out.add(new FolderRow(f, in.size()));
                if (!f.closed || !q.isEmpty()) out.addAll(in);
            }
            List<Object> loose = new ArrayList<>();
            for (Object o : list) if (!own(o) || MarkerStore.folderOf(o) == null) loose.add(o);
            if (!MarkerStore.FOLDERS.isEmpty() && (!loose.isEmpty() || q.isEmpty())) out.add(new FolderRow(null, loose.size()));
            if (MarkerStore.FOLDERS.isEmpty() || !looseClosed || !q.isEmpty()) out.addAll(loose);
            shown = out;
            clampScroll();
            return;
        }
        // titles between the groups: worlds (nearest first) or kinds (by kind)
        List<Object> out = new ArrayList<>();
        String last = null;
        for (Object o : list) {
            String group = sort == 0 ? MapScreen.dimName(dimOf(o)).getString()
                    : sort == 2 ? Component.translatable(o instanceof Marker ? KIND_KEYS[1] : o instanceof MarkerStore.Region ? KIND_KEYS[2] : KIND_KEYS[3]).getString()
                    : null;
            if (group != null && !group.equals(last)) {
                out.add(new Header(group));
                last = group;
            }
            out.add(o);
        }
        shown = out;
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
            if (o instanceof FolderRow fr) {
                boolean drop = dragged != null && i == hover;
                Ui.roundRect(g, px + 8, y + 2, pw - 16, ROW - 4, 4, drop ? 0x88E0B04A : i == hover ? 0x66C8A060 : 0x44A07840);
                g.drawString(font, (fr.folder() == null ? looseClosed : fr.folder().closed) ? "\u25b8" : "\u25be", px + 14, y + 8, Ui.INK, false);
                int fx = px + 26, fy = y + 7; // a little folder
                g.fill(fx, fy, fx + 6, fy + 2, Ui.INK);
                g.fill(fx, fy + 2, fx + 13, fy + 11, Ui.INK);
                g.fill(fx + 1, fy + 3, fx + 12, fy + 10, 0xFFC8963C);
                boolean off = fr.folder() != null && fr.folder().hidden;
                String name = fr.folder() == null ? net.minecraft.client.resources.language.I18n.get("screen.wayfarers_atlas.no_folder") : fr.folder().name;
                g.drawString(font, name + "  (" + fr.count() + ")", px + 44, y + 8, off ? 0xFF8A7556 : Ui.INK, false);
                if (fr.folder() != null) drawEye(g, px + pw - 26, y + ROW / 2, off);
                continue;
            }
            if (o instanceof Header h) { // group title: small, with a thin line after it
                g.drawString(font, h.text(), px + 14, y + 10, 0xFF6B4A26, false);
                int lx = px + 14 + font.width(h.text()) + 6;
                g.fill(lx, y + 14, px + pw - 14, y + 15, 0x553A2410);
                continue;
            }
            if (i == hover) Ui.roundRect(g, px + 8, y + 1, pw - 16, ROW - 2, 4, 0x33FFFFFF);
            int cy = y + ROW / 2;
            if (o instanceof Marker mk) {
                Icons.drawCentered(g, mk.icon, px + 22, cy, 16f, mk.color, mk.off() ? 0.35f : 1f); // hidden: faint
            } else if (o instanceof MarkerStore.Region rg) { // a little patch in the region's colour
                Ui.roundRect(g, px + 15, cy - 6, 14, 12, 3, 0xFF3A2410);
                Ui.roundRect(g, px + 16, cy - 5, 12, 10, 2, (rg.off() ? 0x59000000 : 0xFF000000) | rg.color); // hidden: faint
            } else { // a little ruler
                boolean faint = ((MarkerStore.Ruler) o).off();
                g.fill(px + 15, cy - 2, px + 29, cy + 2, faint ? 0x593A2410 : 0xFF3A2410);
                g.fill(px + 16, cy - 1, px + 28, cy + 1, faint ? 0x59FFD86A : 0xFFFFD86A);
                for (int t = 17; t < 28; t += 3) g.fill(px + t, cy - 4, px + t + 1, cy - 2, 0xFF3A2410);
            }
            g.drawString(font, nameOf(o), px + 36, y + 3, Ui.INK, false);
            double[] pos = posOf(o);
            String sub = MapScreen.dimName(dimOf(o)).getString();
            if (ServerRules.coords()) { // server rule: no coordinates
                sub = (o instanceof Marker mk ? mk.posText() : (int) Math.floor(pos[0]) + ", " + (int) Math.floor(pos[1])) + "  ·  " + sub;
            }
            if (o instanceof Marker mk && mk.serverId != null || o instanceof MarkerStore.Region rg && rg.serverId != null) {
                sub += "  ·  " + net.minecraft.client.resources.language.I18n.get("screen.wayfarers_atlas.server_mark");
            } else if (o instanceof Marker mk && mk.teamUid != null || o instanceof MarkerStore.Region rg && rg.teamUid != null) {
                sub += "  ·  " + net.minecraft.client.resources.language.I18n.get("screen.wayfarers_atlas.team_mark");
            }
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
            boolean none = MarkerStore.allMarkers().isEmpty() && MarkerStore.allRegions().isEmpty() && MarkerStore.RULERS.isEmpty();
            String key = none ? "screen.wayfarers_atlas.no_markers" : "screen.wayfarers_atlas.nothing_found";
            Ui.fitCentered(g, font, Component.translatable(key).getString(), px + pw / 2f, listY + 20, pw - 30, 0xFF7A6040, false);
        }
        g.disableScissor();
        if (dragged != null) { // what is dragged follows the mouse
            if (dragged instanceof Marker dm) Icons.drawCentered(g, dm.icon, mx - 4, my, 14f, dm.color, 0.9f);
            else if (dragged instanceof MarkerStore.Region dr) g.fill(mx - 9, my - 4, mx + 1, my + 4, 0xFF000000 | dr.color);
            else g.fill(mx - 10, my - 1, mx + 1, my + 1, 0xFFFFD86A);
            g.drawString(font, nameOf(dragged), mx + 6, my - 4, Ui.INK, false);
        }
        if (maxScroll() > 0) { // scroll bar
            int barH = Math.max(16, listH * listH / (shown.size() * ROW));
            int barY = listY + (int) ((listH - barH) * (scroll / maxScroll()));
            g.fill(px + pw - 9, listY, px + pw - 7, listY + listH, 0x223A2410);
            g.fill(px + pw - 9, barY, px + pw - 7, barY + barH, 0xAA5A3F22);
        }
        g.pose().popPose();

        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        if (prompt != null) { // asking for a name: a small panel over the list
            if (getFocused() != prompt) { // (the button that opened it takes the focus after its click)
                setFocused(prompt);
                prompt.setFocused(true);
            }
            int bw = 200, bh = 58, bx = (width - bw) / 2, bY = (height - bh) / 2;
            g.fill(0, 0, width, height, 0x55000000);
            Ui.panel(g, bx, bY, bw, bh);
            Ui.fitCentered(g, font, promptTitle, bx + bw / 2f, bY + 9, bw - 16, Ui.INK, false);
            prompt.setPosition(bx + 12, bY + 24);
            prompt.setWidth(bw - 24);
            g.pose().pushPose();
            g.pose().scale(0.75f, 0.75f, 1f);
            String hint = net.minecraft.client.resources.language.I18n.get("screen.wayfarers_atlas.enter_esc");
            g.drawString(font, hint, (int) ((bx + bw / 2f) / 0.75f - font.width(hint) / 2f), (int) ((bY + 45) / 0.75f), 0xFF7A6040, false);
            g.pose().popPose();
        }
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
                && minecraft.player.hasPermissions(2);
    }

    /** World filter: all worlds, or one world that has markers. */
    private void openWorldFilter(int x, int y) {
        List<Entry> list = new ArrayList<>();
        list.add(new Entry(Component.literal(filterDim == null ? "\u2022 " : "   ")
                .append(Component.translatable("screen.wayfarers_atlas.all_worlds")), true, () -> setFilter(null)));
        java.util.TreeSet<String> dims = new java.util.TreeSet<>();
        for (Marker m : MarkerStore.allMarkers()) dims.add(m.dim);
        for (MarkerStore.Region r : MarkerStore.allRegions()) dims.add(r.dim);
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

    /** Which kinds are listed: everything, only markers, only regions, only rulers. */
    private void openKindFilter(int x, int y) {
        List<Entry> list = new ArrayList<>();
        for (int i = 0; i < KIND_KEYS.length; i++) {
            final int k = i;
            list.add(new Entry(Component.literal(i == kindFilter ? "\u2022 " : "   ").append(Component.translatable(KIND_KEYS[i])), true, () -> {
                kindFilter = k;
                scrollTarget = 0;
                rebuildWidgets();
            }));
        }
        showMenu(x, y, list);
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
        if (m.serverId != null) { // a marker of the server: follow it, teleport, hide it in this Atlas - nothing else
            if (AtlasConfig.TELEPORT.get() && ServerRules.teleport()) {
                list.add(new Entry(Component.translatable("menu.wayfarers_atlas.tp_marker"), canTeleport(m), () -> {
                    TeleportHelper.go(m.dim, m.x, m.y, m.z);
                    minecraft.setScreen(null);
                }));
            }
            if (!ServerMarks.LEAD.equals(m.serverId)) {
                list.add(new Entry(Component.translatable(m.hidden ? "menu.wayfarers_atlas.unhide_marker" : "menu.wayfarers_atlas.hide_marker"),
                        true, () -> ServerMarks.setHidden(m, !m.hidden)));
            }
            showMenu(x, y, list);
            return;
        }
        if (!m.death) {
            list.add(new Entry(Component.translatable("menu.wayfarers_atlas.edit"), true,
                    () -> minecraft.setScreen(new MarkerScreen(this, m, false))));
        }
        if (AtlasConfig.TELEPORT.get() && ServerRules.teleport()) {
            list.add(new Entry(Component.translatable("menu.wayfarers_atlas.tp_marker"), canTeleport(m), () -> {
                TeleportHelper.go(m.dim, m.x, m.y, m.z);
                minecraft.setScreen(null);
            }));
        }
        list.add(new Entry(Component.translatable(m.hidden ? "menu.wayfarers_atlas.unhide_marker" : "menu.wayfarers_atlas.hide_marker"),
                true, () -> TeamMarks.setHidden(m, !m.hidden)));
        if (own(m)) list.add(new Entry(Component.translatable("menu.wayfarers_atlas.to_folder"), true, () -> folderMenu(x, y, m)));
        list.add(new Entry(Component.translatable("menu.wayfarers_atlas.share_chat"), true, () -> {
            minecraft.player.connection.sendChat(ShareUtil.encode(m));
            minecraft.setScreen(null);
        }));
        list.add(new Entry(Component.translatable("menu.wayfarers_atlas.delete"), true, () -> {
            TeamMarks.delete(m);
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
        if (o instanceof MarkerStore.Region sr && sr.serverId != null) { // a region of the server: only hidden / shown again
            list.add(new Entry(Component.translatable(sr.hidden ? "menu.wayfarers_atlas.unhide_region" : "menu.wayfarers_atlas.hide_region"),
                    true, () -> ServerMarks.setHidden(sr, !sr.hidden)));
            showMenu(x, y, list);
            return;
        }
        if (o instanceof MarkerStore.Region r) {
            list.add(new Entry(Component.translatable("menu.wayfarers_atlas.edit"), true,
                    () -> minecraft.setScreen(new RegionScreen(this, r, false))));
        }
        if (own(o)) list.add(new Entry(Component.translatable("menu.wayfarers_atlas.to_folder"), true, () -> folderMenu(x, y, o)));
        list.add(new Entry(Component.translatable("menu.wayfarers_atlas.delete"), true, () -> {
            if (o instanceof MarkerStore.Region r) TeamMarks.delete(r);
            else {
                MarkerStore.RULERS.remove((MarkerStore.Ruler) o);
                MarkerStore.save();
            }
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
        if (prompt != null) {
            if (!prompt.isMouseOver(mx, my)) closePrompt(false);
            else prompt.mouseClicked(mx, my, button);
            return true;
        }
        int row = rowAt(mx, my);
        if (row >= 0) {
            Object o = shown.get(row);
            if (o instanceof Header) return true;
            if (o instanceof FolderRow fr) {
                if (button == 0 && fr.folder() != null && mx >= px + pw - 36) { // the eye: hide / show the folder on the maps
                    fr.folder().hidden = !fr.folder().hidden;
                    MarkerStore.save();
                    refresh();
                } else if (button == 0) { // fold / unfold
                    if (fr.folder() == null) looseClosed = !looseClosed;
                    else fr.folder().closed = !fr.folder().closed;
                    refresh();
                } else if (button == 1 && fr.folder() != null) {
                    folderOptions((int) mx, (int) my, fr.folder());
                }
                return true;
            }
            if (button == 0) { // a click shows it on the map; dragging a marker puts it into a folder
                pressRow = row;
                pressX = mx;
                pressY = my;
            } else if (button == 1) openMenu((int) mx, (int) my, o);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (button == 0 && pressRow >= 0 && dragged == null && Math.hypot(mx - pressX, my - pressY) > 4
                && pressRow < shown.size() && own(shown.get(pressRow)) && sort == 3) {
            dragged = shown.get(pressRow);
        }
        if (dragged != null) { // near the edges the list scrolls
            if (my < listY + 10) scrollTarget = Mth.clamp(scrollTarget - 6, 0, maxScroll());
            if (my > listY + listH - 10) scrollTarget = Mth.clamp(scrollTarget + 6, 0, maxScroll());
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0 && dragged != null) {
            int row = rowAt(mx, my);
            if (row >= 0 && shown.get(row) instanceof FolderRow fr) {
                MarkerStore.setFolder(dragged, fr.folder() == null ? null : fr.folder().name);
                MarkerStore.save();
                minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.BOOK_PAGE_TURN, 1.4f, 0.5f));
            }
            dragged = null;
            pressRow = -1;
            refresh();
            return true;
        }
        if (button == 0 && pressRow >= 0) {
            int row = pressRow;
            pressRow = -1;
            if (row < shown.size() && rowAt(mx, my) == row) show(shown.get(row));
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (prompt != null) { // typing goes into the name box, whatever has the focus
            prompt.setFocused(true);
            return prompt.charTyped(c, mods);
        }
        return super.charTyped(c, mods);
    }

    /** Your own markers, regions and rulers can go into folders (not the server's or the team's). */
    private static boolean own(Object o) {
        return MarkerStore.ownItem(o);
    }

    private void drawEye(GuiGraphics g, int cx, int cy, boolean off) {
        int col = Ui.INK, ey = cy - 1;
        g.fill(cx - 3, ey - 3, cx + 3, ey - 2, col);
        g.fill(cx - 5, ey - 2, cx + 5, ey - 1, col);
        g.fill(cx - 6, ey - 1, cx + 6, ey + 2, col);
        g.fill(cx - 5, ey + 2, cx + 5, ey + 3, col);
        g.fill(cx - 3, ey + 3, cx + 3, ey + 4, col);
        g.fill(cx - 4, ey - 1, cx + 4, ey + 2, 0xFFF0E2BC);
        g.fill(cx - 1, ey - 1, cx + 1, ey + 2, col);
        if (off) for (int k = -6; k <= 6; k++) g.fill(cx + k, ey - k / 2 + 1, cx + k + 1, ey - k / 2 + 3, 0xFFA02818); // struck out
    }

    /** Which folder a marker, region or ruler goes into. */
    private void folderMenu(int x, int y, Object m) {
        List<Entry> list = new ArrayList<>();
        String cur = MarkerStore.folderOf(m);
        for (MarkerStore.Folder f : MarkerStore.FOLDERS) {
            list.add(new Entry(Component.literal(f.name.equals(cur) ? "\u2022 " : "   ").append(f.name), true, () -> {
                MarkerStore.setFolder(m, f.name);
                MarkerStore.save();
                refresh();
            }));
        }
        list.add(new Entry(Component.literal(cur == null ? "\u2022 " : "   ")
                .append(Component.translatable("screen.wayfarers_atlas.no_folder")), true, () -> {
            MarkerStore.setFolder(m, null);
            MarkerStore.save();
            refresh();
        }));
        list.add(new Entry(Component.literal("   + ").append(Component.translatable("screen.wayfarers_atlas.new_folder")), true, () -> newFolder(m)));
        showMenu(x, y, list);
    }

    /** Menu of a folder: rename, hide / show, delete (its markers stay, in no folder). */
    private void folderOptions(int x, int y, MarkerStore.Folder f) {
        List<Entry> list = new ArrayList<>();
        list.add(new Entry(Component.translatable("menu.wayfarers_atlas.rename"), true,
                () -> ask(Component.translatable("screen.wayfarers_atlas.rename_folder").getString(), f.name, n -> {
                    String name = unique(n, f);
                    for (Object o : MarkerStore.folderItems()) if (f.name.equals(MarkerStore.folderOf(o))) MarkerStore.setFolder(o, name);
                    f.name = name;
                    MarkerStore.save();
                    refresh();
                })));
        list.add(new Entry(Component.translatable(f.hidden ? "menu.wayfarers_atlas.show_folder" : "menu.wayfarers_atlas.hide_folder"), true, () -> {
            f.hidden = !f.hidden;
            MarkerStore.save();
            refresh();
        }));
        list.add(new Entry(Component.translatable("menu.wayfarers_atlas.delete_folder"), true, () -> {
            for (Object o : MarkerStore.folderItems()) if (f.name.equals(MarkerStore.folderOf(o))) MarkerStore.setFolder(o, null);
            MarkerStore.FOLDERS.remove(f);
            MarkerStore.save();
            refresh();
        }));
        showMenu(x, y, list);
    }

    /** A new folder (asks for its name); the marker, region or ruler given goes into it. */
    private void newFolder(Object m) {
        sort = 3; // folders are seen in the folder view
        ask(Component.translatable("screen.wayfarers_atlas.new_folder").getString(), "", n -> {
            MarkerStore.Folder f = new MarkerStore.Folder(unique(n, null), false);
            MarkerStore.FOLDERS.add(f);
            if (m != null) MarkerStore.setFolder(m, f.name);
            MarkerStore.save();
            rebuildWidgets();
        });
    }

    /** The name, made unique among the folders ("Mines 2"...). */
    private static String unique(String name, MarkerStore.Folder self) {
        String base = MarkerStore.Folder.clean(name);
        if (base.isEmpty()) base = net.minecraft.client.resources.language.I18n.get("screen.wayfarers_atlas.folder");
        String n = base;
        for (int k = 2; ; k++) {
            MarkerStore.Folder other = MarkerStore.folder(n);
            if (other == null || other == self) return n;
            n = MarkerStore.Folder.clean(base + " " + k);
        }
    }

    private void ask(String title, String value, java.util.function.Consumer<String> done) {
        if (prompt != null) removeWidget(prompt);
        promptTitle = title;
        promptDone = done;
        prompt = new EditBox(font, 0, 0, 176, 16, Component.literal(title));
        prompt.setMaxLength(MarkerStore.Folder.MAX_NAME);
        prompt.setValue(value);
        addRenderableWidget(prompt);
        setFocused(prompt);
        prompt.setFocused(true);
    }

    private void closePrompt(boolean ok) {
        if (prompt == null) return;
        String v = prompt.getValue().trim();
        var done = promptDone;
        removeWidget(prompt);
        prompt = null;
        promptDone = null;
        setFocused(search);
        if (ok && !v.isEmpty() && done != null) done.accept(v);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scrollTarget = Mth.clamp(scrollTarget - sy * ROW * 2, 0, maxScroll());
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (prompt != null) {
            prompt.setFocused(true);
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) closePrompt(true);
            else if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) closePrompt(false);
            else prompt.keyPressed(key, scan, mods);
            return true;
        }
        if (menu != null && key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            menu = null;
            return true;
        }
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER) { // Enter: the first one found
            for (Object o : shown) {
                if (o instanceof Header || o instanceof FolderRow) continue;
                show(o);
                return true;
            }
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

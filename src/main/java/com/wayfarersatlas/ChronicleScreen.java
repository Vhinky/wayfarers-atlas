package com.wayfarersatlas;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Chronicle page of the Atlas: the travel record (see Chronicle) on the left, the biomes on your map on the
 * right (by name), leafed through in pages.
 */
public class ChronicleScreen extends Screen {
    private static final int ROW = 13;

    private final Screen parent;
    private int px, py, pw, ph, page = 0, perPage = 1, pages = 1, pageMid = 0;
    private final List<String[]> biomes = new ArrayList<>(); // name, day text ("" = not found)
    private int found = 0, total = 0;
    private String[][] stats;

    public ChronicleScreen(Screen parent) {
        super(Component.translatable("screen.wayfarers_atlas.chronicle"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        pw = Math.min(420, width - 20);
        ph = Math.min(280, height - 20);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        collect();
        perPage = Math.max(1, (ph - 98) / ROW);
        pages = Math.max(1, (biomes.size() + perPage - 1) / perPage);
        page = Math.min(page, pages - 1);
        int by = py + ph - 26;
        addRenderableWidget(new ParchButton(px + pw - 12 - 80, by, 80, 18,
                Component.translatable("screen.wayfarers_atlas.close"), ParchButton.Kind.TEXT, false, b -> onClose()));
        if (pages > 1) { // page arrows and the page number, left of Close (never under it)
            int rightX = px + pw - 12 - 80 - 8 - 20;
            pageMid = rightX - 4 - 20;
            addRenderableWidget(new ParchButton(rightX, by, 20, 18, Component.literal(">"), ParchButton.Kind.TEXT, false, b -> flip(1)));
            addRenderableWidget(new ParchButton(pageMid - 20 - 4 - 20, by, 20, 18, Component.literal("<"), ParchButton.Kind.TEXT, false, b -> flip(-1)));
        }
    }

    private void flip(int d) {
        int np = Math.max(0, Math.min(pages - 1, page + d));
        if (np == page) return;
        page = np;
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.2f, 0.5f));
    }

    private void collect() {
        long days = Chronicle.days(minecraft);
        double w = Chronicle.walked;
        String walked = w >= 1000 ? String.format("%.1f %s", w / 1000, I18n.get("screen.wayfarers_atlas.km"))
                : Math.round(w) + " " + I18n.get("screen.wayfarers_atlas.m");
        int marks = 0;
        for (Marker m : MarkerStore.MARKERS) if (!m.death) marks++;
        String b = I18n.get("screen.wayfarers_atlas.blocks");
        stats = new String[][]{
                {"walked", walked},
                {"explored", Chronicle.chunks() + " " + I18n.get("screen.wayfarers_atlas.chunks")},
                {"days", String.valueOf(days)},
                {"far", Chronicle.far + " " + b},
                {"worlds", I18n.get("screen.wayfarers_atlas.chron.of", Chronicle.DIMS.size(), Chronicle.worldCount(minecraft))},
                {"markers", String.valueOf(marks)},
                {"deaths", String.valueOf(Chronicle.deaths)}};

        // biomes: every biome on the map (and those walked in), by name; then how many are still unknown
        java.util.Set<String> all = new java.util.HashSet<>(Chronicle.BIOMES.keySet());
        Map<String, Long> map = new LinkedHashMap<>();
        Chronicle.addMapBiomes(map);
        all.addAll(map.keySet());
        biomes.clear();
        for (String id : all) biomes.add(new String[]{BiomeNames.name(id), ""});
        biomes.sort((x, z) -> x[0].compareToIgnoreCase(z[0]));
        found = all.size();
        total = Math.max(found, Chronicle.biomeCount(minecraft));
        if (total > found) biomes.add(new String[]{"???", I18n.get("screen.wayfarers_atlas.chron.more", total - found)});
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        if (parent != null) parent.render(g, -1000, -1000, pt); // the map stays visible behind
        g.flush();
        g.pose().pushPose();
        g.pose().translate(0, 0, 700);
        g.fill(0, 0, width, height, 0x66000000);
        Ui.panel(g, px, py, pw, ph);
        Ui.fitCentered(g, font, title.getString(), px + pw / 2f, py + 11, pw - 20, Ui.INK, false);
        g.fill(px + 30, py + 24, px + pw - 30, py + 25, 0xFFB8934A);

        // the record
        int lx = px + 16, colW = pw / 2 - 26, ty = py + 34;
        for (int i = 0; i < stats.length; i++) {
            String label = I18n.get("screen.wayfarers_atlas.chron." + stats[i][0]), value = stats[i][1];
            int yy = ty + i * 15;
            g.drawString(font, label, lx, yy, Ui.INK, false);
            int vw = font.width(value), lw = font.width(label);
            for (int dx = lx + lw + 3; dx < lx + colW - vw - 3; dx += 3) g.fill(dx, yy + 7, dx + 1, yy + 8, 0x66806040); // dots
            g.drawString(font, value, lx + colW - vw, yy, 0xFF2B1D10, false);
        }
        if (!Chronicle.started()) {
            Ui.fitCentered(g, font, I18n.get("screen.wayfarers_atlas.chron.not_started"), lx + colW / 2f, ty + stats.length * 15 + 8,
                    colW, 0xFF7A6040, false);
        }

        // the biomes
        int rx = px + pw / 2 + 6, rw = pw / 2 - 20;
        g.drawString(font, I18n.get("screen.wayfarers_atlas.chron.biomes", found, total), rx, ty, Ui.INK, false);
        g.fill(rx, ty + 12, rx + rw, ty + 17, 0xFF96784F);
        if (total > 0) g.fill(rx, ty + 12, rx + rw * found / total, ty + 17, 0xFF5A8A3C);
        int start = page * perPage;
        for (int i = 0; i < perPage && start + i < biomes.size(); i++) {
            String[] b = biomes.get(start + i);
            int yy = ty + 24 + i * ROW;
            boolean known = !b[0].equals("???");
            g.fill(rx, yy + 1, rx + 8, yy + 9, Ui.INK);
            g.fill(rx + 1, yy + 2, rx + 7, yy + 8, 0xFFE6D1A2);
            if (known) { // a tick
                g.fill(rx + 2, yy + 5, rx + 3, yy + 7, 0xFF3A6B22);
                g.fill(rx + 3, yy + 6, rx + 4, yy + 8, 0xFF3A6B22);
                g.fill(rx + 4, yy + 3, rx + 5, yy + 7, 0xFF3A6B22);
                g.fill(rx + 5, yy + 2, rx + 6, yy + 4, 0xFF3A6B22);
            }
            int dw = (int) (font.width(b[1]) * 0.75f);
            int room = rw - 14 - dw - 6;
            String name = font.width(b[0]) <= room ? b[0] : font.plainSubstrByWidth(b[0], room - font.width("…")) + "…";
            g.drawString(font, name, rx + 12, yy + 1, known ? Ui.INK : 0xFF826848, false);
            g.pose().pushPose();
            g.pose().translate(rx + rw - dw, yy + 2.5f, 0);
            g.pose().scale(0.75f, 0.75f, 1f);
            g.drawString(font, b[1], 0, 0, 0xFF6E5536, false);
            g.pose().popPose();
        }
        if (pages > 1) {
            Ui.fitCentered(g, font, (page + 1) + " / " + pages, pageMid, py + ph - 21, 38, Ui.INK, false);
        }
        super.render(g, mx, my, pt);
        g.pose().popPose();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        flip(sy > 0 ? -1 : 1);
        return true;
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

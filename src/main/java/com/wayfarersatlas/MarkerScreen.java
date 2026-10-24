package com.wayfarersatlas;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Marker dialog: name, colour, an icon from groups (colourable shapes first), then Save.
 * The icon list scrolls with the mouse wheel; the map stays visible behind the dialog.
 */
public class MarkerScreen extends Screen {
    public static final int[] PALETTE = {
            0xD9534A, 0xE08A3C, 0xE6C34A, 0x5FAE5A, 0x4FB0B8, 0x4F7CC4, 0x9A62C0, 0xE08AA8, 0xF2EBDD, 0x4A4038
    };
    private static final int COLS = 12, CELL = 18, PAD = 14, HEADER = 13, GAP = 5, BAR = 6;
    private static final int PW = PAD * 2 + COLS * CELL + BAR + 4;
    private static final int TOP = 82;     // where the icon list starts inside the panel
    private static final int BOTTOM = 46;  // room for the coordinates and the buttons

    private final Screen parent; // may be null (opened from the marker key)
    private final Marker marker;
    private final boolean isNew;
    private EditBox nameBox;
    private int colorIdx = 0;
    private int iconIdx;
    private boolean guard; // the key that opened us is still held: do not let it type into the name box

    private int px, py, ph, areaY, areaH, contentH;
    private double scroll = 0, scrollTarget = 0;
    private boolean draggingBar = false;
    private final int[] groupY = new int[Icons.GROUP_KEYS.length]; // y of each group header inside the content

    public MarkerScreen(Screen parent, Marker marker, boolean isNew) {
        super(Component.translatable("screen.wayfarers_atlas.marker"));
        this.parent = parent;
        this.marker = marker;
        this.isNew = isNew;
        this.iconIdx = Math.floorMod(marker.icon, Icons.COUNT);
        for (int i = 0; i < PALETTE.length; i++) if (PALETTE[i] == marker.color) colorIdx = i;
        this.guard = Keys.physicallyDown(Keys.ADD_MARKER);
    }

    @Override
    protected void init() {
        int y = 0;
        for (int gi = 0; gi < Icons.GROUP_KEYS.length; gi++) {
            groupY[gi] = y;
            int n = Icons.GROUP_TO[gi] - Icons.GROUP_FROM[gi] + 1;
            y += HEADER + ((n + COLS - 1) / COLS) * CELL + GAP;
        }
        contentH = y - GAP;

        ph = Math.min(height - 8, TOP + contentH + BOTTOM + 6);
        px = (width - PW) / 2;
        py = Math.max(4, (height - ph) / 2);
        areaY = py + TOP;
        areaH = ph - TOP - BOTTOM;
        clampScroll();

        nameBox = new EditBox(font, px + PAD, py + 34, PW - PAD * 2, 18, Component.translatable("screen.wayfarers_atlas.name"));
        nameBox.setMaxLength(32);
        nameBox.setValue(marker.name);
        addRenderableWidget(nameBox);
        setInitialFocus(nameBox);

        int bw = (PW - PAD * 2 - 8) / 2;
        int by = py + ph - 28;
        addRenderableWidget(new ParchButton(px + PAD, by, bw, 20,
                Component.translatable("screen.wayfarers_atlas.save"), ParchButton.Kind.TEXT, true, b -> save()));
        addRenderableWidget(new ParchButton(px + PAD + bw + 8, by, bw, 20,
                Component.translatable("screen.wayfarers_atlas.cancel"), ParchButton.Kind.TEXT, false, b -> onClose()));
    }

    private int maxScroll() {
        return Math.max(0, contentH - areaH);
    }

    private void clampScroll() {
        scrollTarget = Mth.clamp(scrollTarget, 0, maxScroll());
        scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    @Override
    public void tick() {
        if (guard && !Keys.physicallyDown(Keys.ADD_MARKER)) guard = false;
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (guard) return true; // swallow key repeats of the opening key
        return super.charTyped(c, mods);
    }

    private void save() {
        String n = nameBox.getValue().trim();
        if (!n.isEmpty()) marker.name = n;
        marker.color = PALETTE[colorIdx];
        marker.icon = iconIdx;
        marker.iconSet = 2;
        if (isNew) MarkerStore.MARKERS.add(marker);
        MarkerStore.save();
        onClose();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** The parent map is drawn by render(), so no blur / background here. */
    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
    }

    // ------------------------------------------------------------------ icon grid geometry

    private int groupOf(int i) {
        for (int gi = 0; gi < Icons.GROUP_KEYS.length; gi++) if (i <= Icons.GROUP_TO[gi]) return gi;
        return Icons.GROUP_KEYS.length - 1;
    }

    private int iconX(int i) {
        int n = i - Icons.GROUP_FROM[groupOf(i)];
        return px + PAD + (n % COLS) * CELL;
    }

    private int iconY(int i) {
        int gi = groupOf(i);
        int n = i - Icons.GROUP_FROM[gi];
        return areaY - (int) Math.round(scroll) + groupY[gi] + HEADER + (n / COLS) * CELL;
    }

    private boolean inArea(double mx, double my) {
        return mx >= px + PAD && mx < px + PAD + COLS * CELL && my >= areaY && my < areaY + areaH;
    }

    private int iconAt(double mx, double my) {
        if (!inArea(mx, my)) return -1;
        for (int i = 0; i < Icons.COUNT; i++) {
            int x = iconX(i), y = iconY(i);
            if (mx >= x && mx < x + CELL && my >= y && my < y + CELL) return i;
        }
        return -1;
    }

    private int barX() {
        return px + PAD + COLS * CELL + 4;
    }

    private boolean onBar(double mx, double my) {
        return maxScroll() > 0 && mx >= barX() - 2 && mx <= barX() + BAR + 2 && my >= areaY && my < areaY + areaH;
    }

    private void barSet(double my) {
        double t = (my - areaY) / (double) areaH;
        scrollTarget = scroll = Mth.clamp(t * contentH - areaH / 2.0, 0, maxScroll());
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        scroll += (scrollTarget - scroll) * 0.35; // smooth scrolling
        if (Math.abs(scrollTarget - scroll) < 0.3) scroll = scrollTarget;

        if (parent != null) parent.render(g, -1000, -1000, pt); // keep the map (or the marker list) visible behind
        g.flush();
        g.pose().pushPose();
        g.pose().translate(0, 0, 700); // above whatever the screen behind drew (the marker list draws high up)
        g.fill(0, 0, width, height, 0x80000000);
        Ui.panel(g, px, py, PW, ph);

        Component title = Component.translatable("screen.wayfarers_atlas.marker");
        g.drawString(font, title, px + (PW - font.width(title)) / 2, py + 10, Ui.INK, false);
        g.drawString(font, Component.translatable("screen.wayfarers_atlas.name"), px + PAD, py + 24, 0xFF6B5030, false);

        // colour swatches (they only affect the first group)
        boolean colourable = Icons.isTinted(iconIdx);
        int sw = (PW - PAD * 2) / PALETTE.length;
        int sy = py + 60;
        for (int i = 0; i < PALETTE.length; i++) {
            int sx = px + PAD + i * sw;
            int col = 0xFF000000 | PALETTE[i];
            if (!colourable) col = Ui.lerpColor(col, 0xFFCDB57F, 0.65f);
            if (i == colorIdx) Ui.roundRect(g, sx - 1, sy - 1, sw, 16, 4, 0xFF3A2410);
            Ui.roundRect(g, sx, sy, sw - 2, 14, 3, col);
        }

        // icon list
        int tint = PALETTE[colorIdx];
        g.fill(px + PAD - 2, areaY - 2, px + PAD + COLS * CELL + 2, areaY + areaH + 2, 0x1A3A2410);
        g.enableScissor(px + PAD - 2, areaY, px + PAD + COLS * CELL + 2, areaY + areaH);
        int hover = iconAt(mx, my);
        for (int gi = 0; gi < Icons.GROUP_KEYS.length; gi++) {
            int hy = areaY - (int) Math.round(scroll) + groupY[gi];
            if (hy > areaY + areaH || hy + HEADER + 200 < areaY) {
                // still draw icons below; cheap enough to just continue
            }
            Component name = Component.translatable("screen.wayfarers_atlas.group." + Icons.GROUP_KEYS[gi]);
            g.drawString(font, name, px + PAD, hy + 2, 0xFF6B5030, false);
            int lx = px + PAD + font.width(name) + 5;
            g.fill(lx, hy + 6, px + PAD + COLS * CELL - 2, hy + 7, 0x553A2410);
            for (int i = Icons.GROUP_FROM[gi]; i <= Icons.GROUP_TO[gi]; i++) {
                int x = iconX(i), y = iconY(i);
                if (y + CELL < areaY || y > areaY + areaH) continue;
                if (i == iconIdx) {
                    Ui.roundRect(g, x, y, CELL, CELL, 4, 0xFFB8934A);
                    Ui.roundRect(g, x + 1, y + 1, CELL - 2, CELL - 2, 3, 0xFFF0E2BC);
                } else if (i == hover) {
                    Ui.roundRect(g, x + 1, y + 1, CELL - 2, CELL - 2, 3, 0x55FFFFFF);
                }
                Icons.drawCentered(g, i, x + CELL / 2f, y + CELL / 2f, 16f, tint, 1f);
            }
        }
        g.disableScissor();

        // fade at the edges of the list, so it is clear that it scrolls
        if (scroll > 0.5) g.fillGradient(px + PAD - 2, areaY, px + PAD + COLS * CELL + 2, areaY + 8, 0x66CDB57F, 0x00CDB57F);
        if (scroll < maxScroll() - 0.5)
            g.fillGradient(px + PAD - 2, areaY + areaH - 8, px + PAD + COLS * CELL + 2, areaY + areaH, 0x00CDB57F, 0x66CDB57F);

        // scrollbar
        if (maxScroll() > 0) {
            int bx = barX();
            Ui.roundRect(g, bx, areaY, BAR, areaH, 3, 0x303A2410);
            int th = Math.max(16, areaH * areaH / contentH);
            int ty = areaY + (int) Math.round((areaH - th) * (scroll / maxScroll()));
            boolean hot = draggingBar || onBar(mx, my);
            Ui.roundRect(g, bx, ty, BAR, th, 3, hot ? 0xFF8A6A3A : 0xFF6B5030);
        }

        String pos = marker.posText();
        g.drawString(font, pos, px + (PW - font.width(pos)) / 2, py + ph - 40, 0xFF6B5030, false);

        super.render(g, mx, my, pt); // text box + buttons (background is disabled above)
        g.pose().popPose();
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            if (onBar(mx, my)) {
                draggingBar = true;
                barSet(my);
                return true;
            }
            int i = iconAt(mx, my);
            if (i >= 0) {
                iconIdx = i;
                return true;
            }
            int sw = (PW - PAD * 2) / PALETTE.length;
            int sy = py + 60;
            if (mx >= px + PAD && mx < px + PAD + PALETTE.length * sw && my >= sy && my < sy + 14) {
                colorIdx = Mth.clamp((int) ((mx - px - PAD) / sw), 0, PALETTE.length - 1);
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (draggingBar) {
            barSet(my);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        draggingBar = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (scrollY != 0 && maxScroll() > 0) {
            scrollTarget = Mth.clamp(scrollTarget - scrollY * CELL * 1.5, 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == 257 || key == 335) { // Enter
            save();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }
}

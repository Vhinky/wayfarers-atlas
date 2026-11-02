package com.wayfarersatlas;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Soft, rounded button with a smooth hover fade; optionally a drawn symbol instead of text. */
public class ParchButton extends Button {
    public enum Kind { TEXT, PLUS, MINUS, TARGET, GEAR, RULER, LIST, REGION, EYE, PALETTE }

    private final Kind kind;
    private final boolean primary;
    private float hover = 0f;
    private long lastMs = Util.getMillis();

    public ParchButton(int x, int y, int w, int h, Component msg, Kind kind, boolean primary, OnPress press) {
        super(x, y, w, h, msg, press, DEFAULT_NARRATION);
        this.kind = kind;
        this.primary = primary;
    }

    @Override
    public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
        long now = Util.getMillis();
        float dt = Math.min(0.1f, (now - lastMs) / 1000f);
        lastMs = now;
        float target = isHoveredOrFocused() ? 1f : 0f;
        hover += (target - hover) * Math.min(1f, dt * 14f);

        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        int top = Ui.lerpColor(primary ? 0xFFDDB45E : 0xFFD6BC8A, primary ? 0xFFEBC773 : 0xFFE6D1A2, hover);
        int bot = Ui.lerpColor(primary ? 0xFFBF9440 : 0xFFB89868, primary ? 0xFFCDA44E : 0xFFC9AB7A, hover);

        Ui.roundRect(g, x, y + 1, w, h, 5, 0x30000000);                 // soft shadow
        Ui.roundRect(g, x, y, w, h, 5, 0x88503418);                      // thin soft border
        Ui.roundGradient(g, x + 1, y + 1, w - 2, h - 2, 4, top, bot);

        int cx = x + w / 2, cy = y + h / 2;
        int col = 0xFF3A2410;
        switch (kind) {
            case PLUS -> {
                g.fill(cx - 4, cy - 1, cx + 4, cy + 1, col);
                g.fill(cx - 1, cy - 4, cx + 1, cy + 4, col);
            }
            case MINUS -> g.fill(cx - 4, cy - 1, cx + 4, cy + 1, col);
            case TARGET -> {
                g.fill(cx - 4, cy - 4, cx + 4, cy - 3, col);   // ring: 8 x 8, all four corners complete
                g.fill(cx - 4, cy + 3, cx + 4, cy + 4, col);
                g.fill(cx - 4, cy - 3, cx - 3, cy + 3, col);
                g.fill(cx + 3, cy - 3, cx + 4, cy + 3, col);
                g.fill(cx - 1, cy - 1, cx + 1, cy + 1, col);   // centre dot
                g.fill(cx - 1, cy - 7, cx + 1, cy - 4, col);   // ticks
                g.fill(cx - 1, cy + 4, cx + 1, cy + 7, col);
                g.fill(cx - 7, cy - 1, cx - 4, cy + 1, col);
                g.fill(cx + 4, cy - 1, cx + 7, cy + 1, col);
            }
            case GEAR -> {
                g.fill(cx - 3, cy - 4, cx + 3, cy + 4, col);   // body
                g.fill(cx - 4, cy - 3, cx + 4, cy + 3, col);
                g.fill(cx - 1, cy - 6, cx + 1, cy + 6, col);   // teeth
                g.fill(cx - 6, cy - 1, cx + 6, cy + 1, col);
                g.fill(cx - 5, cy - 5, cx - 3, cy - 3, col);
                g.fill(cx + 3, cy - 5, cx + 5, cy - 3, col);
                g.fill(cx - 5, cy + 3, cx - 3, cy + 5, col);
                g.fill(cx + 3, cy + 3, cx + 5, cy + 5, col);
                g.fill(cx - 1, cy - 1, cx + 1, cy + 1, 0xFFE6D1A2); // hole
            }
            case LIST -> { // a list: three rows, each with a dot
                for (int i = -1; i <= 1; i++) {
                    int ry = cy + i * 4;
                    g.fill(cx - 6, ry - 1, cx - 4, ry + 1, col);
                    g.fill(cx - 2, ry - 1, cx + 6, ry + 1, col);
                }
            }
            case RULER -> pattern(g, cx, cy, new String[]{ // a straight gold ruler with marks
                    "KKKKKKKKKKKKKK",
                    "KLLLLLLLLLLLLK",
                    "KLKLKLKLKLKLLK",
                    "KLKLLLKLLLKLLK",
                    "KGGGGGGGGGGGGK",
                    "KKKKKKKKKKKKKK"}, col);
            case PALETTE -> pattern(g, cx, cy, new String[]{ // a map sheet: half in normal colours, half old-style
                    "KKKKKKKKKKKK",
                    "KeeeeeeeeesK",
                    "KeebeeeeessK",
                    "KebbbeeesssK",
                    "KeebeeessssK",
                    "KeeeeesssssK",
                    "KeeeesssssbK",
                    "KeeesssssbbK",
                    "KeessssssssK",
                    "KKKKKKKKKKKK"}, col);
            case EYE -> { // an eye: 12 x 7, centred in the button
                int ey = cy - 1;
                g.fill(cx - 3, ey - 3, cx + 3, ey - 2, col);
                g.fill(cx - 5, ey - 2, cx + 5, ey - 1, col);
                g.fill(cx - 6, ey - 1, cx + 6, ey + 2, col);
                g.fill(cx - 5, ey + 2, cx + 5, ey + 3, col);
                g.fill(cx - 3, ey + 3, cx + 3, ey + 4, col);
                g.fill(cx - 4, ey - 1, cx + 4, ey + 2, 0xFFF0E2BC); // white of the eye
                g.fill(cx - 1, ey - 1, cx + 1, ey + 2, col);        // pupil
            }
            case REGION -> pattern(g, cx, cy, new String[]{ // a dashed selection frame with a filled middle
                    "KK.KK.KK.KK.",
                    "K..........K",
                    "..ffffffff..",
                    "K.ffffffff.K",
                    "K.ffffffff.K",
                    "..ffffffff..",
                    "K.ffffffff.K",
                    "K.ffffffff.K",
                    "..ffffffff..",
                    "K..........K",
                    ".KK.KK.KK.KK"}, col);
            default -> g.drawString(Ui.font(), getMessage(), cx - Ui.font().width(getMessage()) / 2, y + (h - 8) / 2 + 1, col, false);
        }
    }

    /** Pixel picture centred on (cx, cy): K ink, L light gold, G gold, f faint brown, . nothing. */
    private static void pattern(GuiGraphics g, int cx, int cy, String[] rows, int ink) {
        int h = rows.length, w = rows[0].length();
        int x0 = cx - w / 2, y0 = cy - h / 2;
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
                int color = switch (rows[r].charAt(c)) {
                    case 'K' -> ink;
                    case 'L' -> 0xFFF6DA8A;
                    case 'G' -> 0xFFE0B04A;
                    case 'f' -> 0x8C966E3C;
                    case 'e' -> 0xFF70A048;
                    case 'b' -> 0xFF4F7CC4;
                    case 's' -> 0xFFC4AA78;
                    default -> 0;
                };
                if (color != 0) g.fill(x0 + c, y0 + r, x0 + c + 1, y0 + r + 1, color);
            }
        }
    }
}

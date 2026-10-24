package com.wayfarersatlas;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Soft, rounded button with a smooth hover fade; optionally a drawn symbol instead of text. */
public class ParchButton extends Button {
    public enum Kind { TEXT, PLUS, MINUS, TARGET, GEAR, RULER }

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
            case RULER -> { // a little ruler with ticks, slanted
                for (int i = 0; i < 12; i++) {
                    int px = cx - 6 + i, py = cy + 3 - i / 2;
                    g.fill(px, py - 2, px + 1, py + 2, col);
                }
                for (int i = 1; i < 12; i += 3) { // ticks
                    int px = cx - 6 + i, py = cy + 3 - i / 2;
                    g.fill(px, py - 4, px + 1, py - 2, col);
                }
            }
            default -> g.drawString(Ui.font(), getMessage(), cx - Ui.font().width(getMessage()) / 2, y + (h - 8) / 2 + 1, col, false);
        }
    }
}

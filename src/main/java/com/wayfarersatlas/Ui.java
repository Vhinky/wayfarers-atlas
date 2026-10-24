package com.wayfarersatlas;

import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.player.AbstractClientPlayer;

/** Shared papyrus / wooden-frame look. */
public final class Ui {
    public static final int DARK = 0xFF2B1D10;
    public static final int BROWN = 0xFF5A3F22;
    public static final int GOLD = 0xFFB8934A;
    public static final int GOLD_LIGHT = 0xFFE8D29A;
    public static final int PARCH_TOP = 0xFFE3D1A6;
    public static final int PARCH_BOT = 0xFFCDB57F;
    public static final int INK = 0xFF3A2410;

    /** Wooden frame with a gold inlay; the content area starts 5px inside. */
    public static void frame(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, DARK);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, BROWN);
        g.fill(x + 3, y + 3, x + w - 3, y + h - 3, GOLD);
        g.fill(x + 4, y + 4, x + w - 4, y + h - 4, DARK);
        // corner studs
        int[][] corners = {{x + 1, y + 1}, {x + w - 4, y + 1}, {x + 1, y + h - 4}, {x + w - 4, y + h - 4}};
        for (int[] c : corners) {
            g.fill(c[0], c[1], c[0] + 3, c[1] + 3, GOLD_LIGHT);
        }
    }

    /** A filled circle, drawn row by row. */
    public static void disc(GuiGraphics g, double cx, double cy, double r, int color) {
        for (int yy = (int) Math.floor(cy - r); yy < (int) Math.ceil(cy + r); yy++) {
            double dy = yy + 0.5 - cy;
            if (Math.abs(dy) > r) continue;
            double h = Math.sqrt(r * r - dy * dy);
            g.fill((int) Math.round(cx - h), yy, (int) Math.round(cx + h), yy + 1, color);
        }
    }

    /** Round frame for the round minimap: dark edge, wood, a gold ring, four gold studs. */
    public static void roundFrame(GuiGraphics g, double cx, double cy, double r) {
        disc(g, cx, cy, r + 5, DARK);
        disc(g, cx, cy, r + 4, BROWN);
        disc(g, cx, cy, r + 2.5, GOLD);
        disc(g, cx, cy, r + 1.5, DARK);
        for (int i = 0; i < 4; i++) {
            double a = Math.PI / 4 + i * Math.PI / 2;
            int sx = (int) Math.round(cx + Math.cos(a) * (r + 3.2)), sy = (int) Math.round(cy + Math.sin(a) * (r + 3.2));
            g.fill(sx - 1, sy - 1, sx + 1, sy + 1, GOLD_LIGHT);
        }
    }

    /** Parchment panel inside a wooden frame (dialogs). */
    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        frame(g, x, y, w, h);
        g.fillGradient(x + 5, y + 5, x + w - 5, y + h - 5, PARCH_TOP, PARCH_BOT);
    }

    /** Dark plaque used for coordinates and labels. */
    public static void plaque(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, DARK);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, GOLD);
        g.fill(x + 2, y + 2, x + w - 2, y + h - 2, 0xFF33230F);
    }

    /** Soft shadow along the inside edges of a map area. */
    public static void innerShadow(GuiGraphics g, int x, int y, int w, int h) {
        int s = 6;
        g.fillGradient(x, y, x + w, y + s, 0x66000000, 0x00000000);
        g.fillGradient(x, y + h - s, x + w, y + h, 0x00000000, 0x66000000);
    }

    /** Text centered on cx, shrunk if wider than maxW. */
    public static void fitCentered(GuiGraphics g, Font font, String text, float cx, float y, float maxW, int color, boolean shadow) {
        float tw = font.width(text);
        float scale = tw > maxW ? maxW / tw : 1f;
        g.pose().pushPose();
        g.pose().translate(cx, y, 0);
        g.pose().scale(scale, scale, 1f);
        g.drawString(font, text, (int) (-tw / 2f), 0, color, shadow);
        g.pose().popPose();
    }

    /**
     * Overall opacity of what is being drawn right now (the minimap fades in / out). Every place that changes the
     * shader colour multiplies by it, so nothing pops back to full opacity in the middle of a fade.
     */
    public static float alpha = 1f;

    /**
     * Pictures (map, faces, icons) are drawn straight away, and the frame's fills switch blending off after
     * themselves; without this the map would stay solid and vanish at once at the end of a fade.
     */
    public static void blendForFade() {
        if (alpha < 1f) {
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        }
    }

    /** GUI pixels for a size in real screen pixels. */
    public static float px(float real) {
        return (float) (real / Minecraft.getInstance().getWindow().getGuiScale());
    }

    /** A player's face (skin works for other players in multiplayer too), optionally with a heading pointer.
     *  The size snaps to whole screen pixels per skin pixel so the face is always crisp. */
    public static void head(GuiGraphics g, AbstractClientPlayer p, double sx, double sy, float desired, boolean pointer, float yaw) {
        double sc = Minecraft.getInstance().getWindow().getGuiScale();
        int k = Math.max(1, (int) Math.round(desired * sc / 8.0));
        float s = (float) (k / sc);
        double px = Math.round(sx * sc) / sc, py = Math.round(sy * sc) / sc;
        g.pose().pushPose();
        g.pose().translate((float) px, (float) py, 0f);
        g.pose().scale(s, s, 1f);
        if (pointer) {
            g.pose().pushPose();
            g.pose().mulPose(Axis.ZP.rotationDegrees(yaw + 180f));
            for (int i = 0; i < 4; i++) {
                g.fill(-i - 1, -12 + i, i + 2, -10 + i, 0xFF000000);
            }
            for (int i = 0; i < 4; i++) {
                g.fill(-i, -11 + i, i + 1, -10 + i, 0xFFFFFFFF);
            }
            g.pose().popPose();
        }
        g.fill(-5, -5, 5, 5, 0xFF000000);
        blendForFade();
        PlayerFaceRenderer.draw(g, p.getSkin(), -4, -4, 8);
        g.pose().popPose();
    }

    /** Rounded rectangle (radius r) with a vertical gradient, drawn row by row. */
    public static void roundGradient(GuiGraphics g, int x, int y, int w, int h, int r, int top, int bottom) {
        r = Math.min(r, Math.min(w, h) / 2);
        for (int i = 0; i < h; i++) {
            int inset = 0;
            int edge = Math.min(i, h - 1 - i);
            if (edge < r) {
                double dy = r - edge - 0.5;
                inset = (int) Math.ceil(r - Math.sqrt(Math.max(0, (double) r * r - dy * dy)));
            }
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, lerpColor(top, bottom, h <= 1 ? 0f : i / (float) (h - 1)));
        }
    }

    public static void roundRect(GuiGraphics g, int x, int y, int w, int h, int r, int color) {
        roundGradient(g, x, y, w, h, r, color, color);
    }

    public static int lerpColor(int a, int b, float t) {
        int aa = (a >>> 24), ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24), br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    public static Font font() {
        return Minecraft.getInstance().font;
    }

    private Ui() {}
}

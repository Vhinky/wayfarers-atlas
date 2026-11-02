package com.wayfarersatlas;

import com.wayfarersatlas.AtlasConfig.Theme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/** Map themes: pick how the map is drawn. Each row shows a little sample of the theme; the choice applies at once. */
public class ThemeScreen extends Screen {
    private static final int PW = 220, ROW = 26, PAD = 12;
    // sample colours of each theme (ARGB): land, forest, water, mountain
    private static final int[][] SAMPLES = {
            {0xFF70A048, 0xFF40703A, 0xFF4060AC, 0xFFE8E8EC},
            {0xFFA8A27A, 0xFF7E7A58, 0xFF7E8494, 0xFFE6DCC4},
            {0xFFB5C394, 0xFF8FA77A, 0xFF97A8C4, 0xFFF0EDE6},
            {0xFF8CB464, 0xFFC8C878, 0xFFBE8C5A, 0xFFF5F5F5}};

    private final Screen parent;
    private int px, py, ph;

    public ThemeScreen(Screen parent) {
        super(Component.translatable("screen.wayfarers_atlas.themes"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ph = 34 + Theme.values().length * ROW + 34;
        px = (width - PW) / 2;
        py = (height - ph) / 2;
        addRenderableWidget(new ParchButton(px + PW - PAD - 90, py + ph - 28, 90, 20,
                Component.translatable("screen.wayfarers_atlas.close"), ParchButton.Kind.TEXT, false, b -> onClose()));
    }

    private int rowY(int i) {
        return py + 30 + i * ROW;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        if (parent != null) parent.render(g, -1000, -1000, pt); // the map stays visible behind and shows the theme
        g.flush();
        g.pose().pushPose();
        g.pose().translate(0, 0, 700);
        Ui.panel(g, px, py, PW, ph);
        Ui.fitCentered(g, font, title.getString(), px + PW / 2f, py + 11, PW - 20, Ui.INK, false);
        Theme cur = MapThemes.current();
        for (Theme t : Theme.values()) {
            int i = t.ordinal(), y = rowY(i);
            boolean hot = mx >= px + 8 && mx < px + PW - 8 && my >= y && my < y + ROW - 2;
            if (t == cur) Ui.roundRect(g, px + 8, y, PW - 16, ROW - 2, 4, 0x55B8934A);
            else if (hot) Ui.roundRect(g, px + 8, y, PW - 16, ROW - 2, 4, 0x33FFFFFF);
            // a little sample: four colour squares in a frame
            int sx = px + PAD + 2, sy = y + 4;
            g.fill(sx - 1, sy - 1, sx + 33, sy + 17, Ui.INK);
            for (int k = 0; k < 4; k++) g.fill(sx + k * 8, sy, sx + k * 8 + 8, sy + 16, SAMPLES[i][k]);
            g.drawString(font, Component.translatable("wayfarers_atlas.theme." + t.name().toLowerCase()), px + PAD + 44, y + 8,
                    t == cur ? 0xFF3A2410 : Ui.INK, false);
            if (t == cur) g.drawString(font, "✔", px + PW - PAD - 10, y + 8, 0xFF3A6B22, false);
        }
        super.render(g, mx, my, pt);
        g.pose().popPose();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (Theme t : Theme.values()) {
                int y = rowY(t.ordinal());
                if (mx >= px + 8 && mx < px + PW - 8 && my >= y && my < y + ROW - 2) {
                    AtlasConfig.THEME.set(t);
                    AtlasConfig.THEME.save();
                    minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.2f, 0.5f));
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
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

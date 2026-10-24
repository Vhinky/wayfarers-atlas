package com.wayfarersatlas;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * Opened by right-clicking a map table with an Atlas. One button exchanges the records: what you explored goes
 * onto the table, what others left there comes into your Atlas (faded until you go there yourself).
 * Two ticks decide what happens with markers.
 */
public class TableScreen extends Screen {
    private static boolean share = false; // leave my markers on the table
    private static boolean take = true;   // take the markers that are on the table

    private static final int PW = 236, PH = 142;
    private final BlockPos pos;
    private int px, py;

    public TableScreen(BlockPos pos) {
        super(Component.translatable("block.wayfarers_atlas.map_table"));
        this.pos = pos;
    }

    @Override
    protected void init() {
        px = (width - PW) / 2;
        py = (height - PH) / 2;
        int bw = (PW - 28 - 8) / 2, by = py + PH - 30;
        addRenderableWidget(new ParchButton(px + 14, by, bw, 20,
                Component.translatable("screen.wayfarers_atlas.table.exchange"), ParchButton.Kind.TEXT, true, b -> exchange()));
        addRenderableWidget(new ParchButton(px + 14 + bw + 8, by, bw, 20,
                Component.translatable("screen.wayfarers_atlas.cancel"), ParchButton.Kind.TEXT, false, b -> onClose()));
    }

    private void exchange() {
        AtlasSync.flush(Integer.MAX_VALUE); // everything mapped so far is in the Atlas before the table reads it
        List<String> codes = share ? AtlasSync.markerCodes() : List.of();
        PacketDistributor.sendToServer(new Net.TableExchange(pos, share, take, codes));
        onClose();
    }

    private int rowY(int i) {
        return py + 66 + i * 18;
    }

    private boolean inRow(int i, double mx, double my) {
        int y = rowY(i);
        return mx >= px + 12 && mx < px + PW - 12 && my >= y - 3 && my < y + 13;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (int i = 0; i < 2; i++) {
                if (inRow(i, mx, my)) {
                    if (i == 0) share = !share;
                    else take = !take;
                    minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        super.renderBackground(g, mx, my, pt);
        Ui.panel(g, px, py, PW, PH);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        Ui.fitCentered(g, font, title.getString(), px + PW / 2f, py + 13, PW - 30, Ui.INK, false);
        g.fill(px + 30, py + 25, px + PW - 30, py + 26, 0x553A2410);
        int muted = 0xFF6B4A26;
        Ui.fitCentered(g, font, Component.translatable("screen.wayfarers_atlas.table.info1").getString(),
                px + PW / 2f, py + 33, PW - 24, muted, false);
        Ui.fitCentered(g, font, Component.translatable("screen.wayfarers_atlas.table.info2").getString(),
                px + PW / 2f, py + 44, PW - 24, muted, false);
        check(g, 0, share, "screen.wayfarers_atlas.table.share", mx, my);
        check(g, 1, take, "screen.wayfarers_atlas.table.take", mx, my);
    }

    private void check(GuiGraphics g, int i, boolean on, String key, int mx, int my) {
        int x = px + 16, y = rowY(i);
        if (inRow(i, mx, my)) Ui.roundRect(g, px + 10, y - 4, PW - 20, 18, 4, 0x22FFFFFF);
        Ui.roundRect(g, x, y, 11, 11, 2, 0xFF5A3F22);
        Ui.roundRect(g, x + 1, y + 1, 9, 9, 2, on ? 0xFFF3E6C4 : 0xFFE2D2AA);
        if (on) { // tick mark
            int c = 0xFF3A6B22;
            g.fill(x + 2, y + 5, x + 4, y + 7, c);
            g.fill(x + 3, y + 6, x + 5, y + 9, c);
            g.fill(x + 5, y + 5, x + 7, y + 8, c);
            g.fill(x + 6, y + 3, x + 8, y + 6, c);
            g.fill(x + 7, y + 2, x + 9, y + 4, c);
        }
        g.drawString(font, Component.translatable(key), x + 17, y + 2, Ui.INK, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

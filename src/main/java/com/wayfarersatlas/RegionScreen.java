package com.wayfarersatlas;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** Region dialog: name and colour of an area drawn on the map, then Save. The map stays visible behind it. */
public class RegionScreen extends Screen {
    private static final int PW = 220, PH = 118, PAD = 14;

    private final Screen parent;
    private final MarkerStore.Region region;
    private final boolean isNew;
    private EditBox nameBox;
    private int colorIdx = 0;
    private int px, py;

    public RegionScreen(Screen parent, MarkerStore.Region region, boolean isNew) {
        super(Component.translatable("screen.wayfarers_atlas.region"));
        this.parent = parent;
        this.region = region;
        this.isNew = isNew;
        for (int i = 0; i < MarkerScreen.PALETTE.length; i++) if (MarkerScreen.PALETTE[i] == region.color) colorIdx = i;
    }

    @Override
    protected void init() {
        px = (width - PW) / 2;
        py = (height - PH) / 2;
        nameBox = new EditBox(font, px + PAD, py + 34, PW - PAD * 2, 18, Component.translatable("screen.wayfarers_atlas.name"));
        nameBox.setMaxLength(32);
        nameBox.setValue(region.name);
        addRenderableWidget(nameBox);
        setInitialFocus(nameBox);
        int bw = (PW - PAD * 2 - 8) / 2, by = py + PH - 28;
        addRenderableWidget(new ParchButton(px + PAD, by, bw, 20,
                Component.translatable("screen.wayfarers_atlas.save"), ParchButton.Kind.TEXT, true, b -> save()));
        addRenderableWidget(new ParchButton(px + PAD + bw + 8, by, bw, 20,
                Component.translatable("screen.wayfarers_atlas.cancel"), ParchButton.Kind.TEXT, false, b -> onClose()));
    }

    private int swatchW() {
        return (PW - PAD * 2) / MarkerScreen.PALETTE.length;
    }

    private void save() {
        String n = nameBox.getValue().trim();
        if (!n.isEmpty()) region.name = n;
        region.color = MarkerScreen.PALETTE[colorIdx];
        if (isNew) MarkerStore.REGIONS.add(region);
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

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        if (parent != null) parent.render(g, -1000, -1000, pt); // the map stays visible behind
        g.flush();
        g.pose().pushPose();
        g.pose().translate(0, 0, 700);
        g.fill(0, 0, width, height, 0x80000000);
        Ui.panel(g, px, py, PW, PH);
        Component title = Component.translatable("screen.wayfarers_atlas.region");
        g.drawString(font, title, px + (PW - font.width(title)) / 2, py + 10, Ui.INK, false);
        g.drawString(font, Component.translatable("screen.wayfarers_atlas.name"), px + PAD, py + 24, 0xFF6B5030, false);
        int sw = swatchW(), sy = py + 60;
        for (int i = 0; i < MarkerScreen.PALETTE.length; i++) {
            int sx = px + PAD + i * sw;
            if (i == colorIdx) Ui.roundRect(g, sx - 1, sy - 1, sw, 16, 4, 0xFF3A2410);
            Ui.roundRect(g, sx, sy, sw - 2, 14, 3, 0xFF000000 | MarkerScreen.PALETTE[i]);
        }
        super.render(g, mx, my, pt);
        g.pose().popPose();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int sw = swatchW(), sy = py + 60;
        if (button == 0 && mx >= px + PAD && mx < px + PAD + MarkerScreen.PALETTE.length * sw && my >= sy && my < sy + 14) {
            colorIdx = Mth.clamp((int) ((mx - px - PAD) / sw), 0, MarkerScreen.PALETTE.length - 1);
            return true;
        }
        return super.mouseClicked(mx, my, button);
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

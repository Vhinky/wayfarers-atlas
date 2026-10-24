package com.wayfarersatlas;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/** Draws the Atlas slot on the left of the survival inventory and handles clicks on it. */
public final class AtlasSlotUi {
    private static final ItemStack GHOST = new ItemStack(WayfarersAtlas.ATLAS.get());

    /**
     * Top-left corner of the 18x18 slot, or null if it is not shown on this screen.
     * Right of the inventory, level with the "Crafting" title. It moves down past potion effects and past anything
     * other mods put there (buttons, extra slots), and can be shifted in the settings.
     */
    private static int[] pos(Object screen) {
        if (!(screen instanceof InventoryScreen inv)) return null;
        int x = inv.getGuiLeft() + inv.getXSize() + 9 + AtlasConfig.SLOT_X.get();
        int y = inv.getGuiTop() + 6 + AtlasConfig.SLOT_Y.get();
        var mc = Minecraft.getInstance();
        if (mc.player != null && AtlasConfig.SLOT_X.get() == 0 && AtlasConfig.SLOT_Y.get() == 0) {
            long shown = mc.player.getActiveEffects().stream().filter(e -> e.showIcon()).count();
            if (shown > 0) y += (int) Math.min(166, shown > 5 ? 166 : shown * 33); // effects are listed there
        }
        for (int tries = 0; tries < 12 && blocked(inv, x, y); tries++) y += 24; // something else is there: go down
        return new int[]{x, y};
    }

    /** Does a button or a slot of another mod cover this place? */
    private static boolean blocked(InventoryScreen inv, int x, int y) {
        int x0 = x - 5, y0 = y - 5, x1 = x + 21, y1 = y + 21;
        for (var c : inv.children()) {
            if (c instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible) {
                if (w.getX() < x1 && w.getX() + w.getWidth() > x0 && w.getY() < y1 && w.getY() + w.getHeight() > y0) return true;
            }
        }
        for (var s : inv.getMenu().slots) {
            int sx = inv.getGuiLeft() + s.x, sy = inv.getGuiTop() + s.y;
            if (s.isActive() && sx < x1 && sx + 16 > x0 && sy < y1 && sy + 16 > y0) return true;
        }
        return false;
    }

    private static boolean pressedHere = false;

    /** The press on our slot was handled: the release must not reach the screen (it would drop the item). */
    public static void onRelease(ScreenEvent.MouseButtonReleased.Pre e) {
        if (pressedHere) {
            pressedHere = false;
            e.setCanceled(true);
        }
    }

    private static boolean over(int[] p, double mx, double my) {
        return p != null && mx >= p[0] - 1 && mx < p[0] + 17 && my >= p[1] - 1 && my < p[1] + 17;
    }

    public static void onRender(ScreenEvent.Render.Post e) {
        int[] p = pos(e.getScreen());
        if (p == null) return;
        GuiGraphics g = e.getGuiGraphics();
        int x = p[0], y = p[1];
        // little parchment tab with a wooden edge, then a vanilla-looking slot
        g.fill(x - 5, y - 5, x + 21, y + 21, Ui.DARK);
        g.fill(x - 4, y - 4, x + 20, y + 20, Ui.BROWN);
        g.fillGradient(x - 3, y - 3, x + 19, y + 19, Ui.PARCH_TOP, Ui.PARCH_BOT);
        g.fill(x - 1, y - 1, x + 17, y, 0xFF373737);
        g.fill(x - 1, y, x, y + 17, 0xFF373737);
        g.fill(x, y + 16, x + 17, y + 17, 0xFFFFFFFF);
        g.fill(x + 16, y, x + 17, y + 16, 0xFFFFFFFF);
        g.fill(x, y, x + 16, y + 16, 0xFF8B8B8B);

        ItemStack s = AtlasSlot.clientStack;
        if (s.isEmpty()) { // faint outline of an Atlas: shows what belongs here
            g.renderItem(GHOST, x, y);
            g.pose().pushPose();
            g.pose().translate(0, 0, 200);
            g.fill(x, y, x + 16, y + 16, 0xB08B8B8B);
            g.pose().popPose();
        } else {
            g.renderItem(s, x, y);
            g.renderItemDecorations(Minecraft.getInstance().font, s, x, y);
        }
        int mx = e.getMouseX(), my = e.getMouseY();
        if (over(p, mx, my)) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 200);
            g.fill(x, y, x + 16, y + 16, 0x80FFFFFF);
            g.pose().popPose();
            var mc = Minecraft.getInstance();
            boolean carrying = mc.player != null && !mc.player.containerMenu.getCarried().isEmpty();
            if (!carrying) {
                if (s.isEmpty()) {
                    g.renderComponentTooltip(mc.font, List.of(
                            Component.translatable("screen.wayfarers_atlas.atlas_slot").withStyle(ChatFormatting.GOLD),
                            Component.translatable("screen.wayfarers_atlas.atlas_slot.desc").withStyle(ChatFormatting.GRAY)), mx, my);
                } else {
                    g.renderTooltip(mc.font, s, mx, my);
                }
            }
        }
    }

    public static void onClick(ScreenEvent.MouseButtonPressed.Pre e) {
        int[] p = pos(e.getScreen());
        if (p == null) return;
        boolean shift = net.minecraft.client.gui.screens.Screen.hasShiftDown();
        if (over(p, e.getMouseX(), e.getMouseY())) {
            if (e.getButton() == 0 || e.getButton() == 1) {
                int action = shift ? AtlasSlot.SHIFT : e.getButton() == 1 ? AtlasSlot.RIGHT : AtlasSlot.LEFT;
                PacketDistributor.sendToServer(new Net.AtlasSlotClick(action, -1));
                e.setCanceled(true);
                pressedHere = true;
            }
            return;
        }
        // shift-click on an Atlas in the inventory: straight into the Atlas slot (if it is free)
        if (shift && e.getButton() == 0 && AtlasSlot.clientStack.isEmpty() && e.getScreen() instanceof InventoryScreen inv) {
            var slot = inv.getSlotUnderMouse();
            if (slot != null && slot.getItem().is(WayfarersAtlas.ATLAS.get())) {
                PacketDistributor.sendToServer(new Net.AtlasSlotClick(AtlasSlot.SHIFT_IN, slot.index));
                e.setCanceled(true);
                pressedHere = true;
            }
        }
    }

    private AtlasSlotUi() {}
}

package com.wayfarersatlas;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The Atlas slot: a place for one Atlas next to the inventory. An Atlas kept there stays with you when you die.
 * The server keeps it on the player (saved with the player, copied on death); the client gets a copy to draw it.
 */
public final class AtlasSlot {
    /** Client copy of the slot (set by the server). */
    static ItemStack clientStack = ItemStack.EMPTY;

    public static ItemStack get(Player p) {
        if (p.level().isClientSide) return clientStack;
        return p.getData(WayfarersAtlas.ATLAS_SLOT.get());
    }

    /** What each player's game was last told is in their slot. */
    private static final java.util.Map<java.util.UUID, ItemStack> SENT = new java.util.HashMap<>();
    /** Players to be told again a moment after respawning (in case the first message got lost on the way). */
    private static final java.util.Map<java.util.UUID, Integer> AGAIN = new java.util.HashMap<>();

    public static void sync(ServerPlayer p) {
        ItemStack s = get(p).copy();
        SENT.put(p.getUUID(), s.copy());
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new Net.AtlasSlotSync(s));
    }

    /** After respawning: now, and once more a second later. */
    public static void syncAfterRespawn(ServerPlayer p) {
        sync(p);
        AGAIN.put(p.getUUID(), 20);
    }

    /**
     * Every few ticks: if the slot changed without the player's game being told (or a message got lost), it is told
     * now - so the slot never shows an empty place while the Atlas is really there.
     */
    public static void tick(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            Integer again = AGAIN.get(p.getUUID());
            if (again != null) {
                if (again <= 0) {
                    AGAIN.remove(p.getUUID());
                    sync(p);
                    continue;
                }
                AGAIN.put(p.getUUID(), again - 1);
            }
            ItemStack last = SENT.get(p.getUUID());
            if (last == null || !ItemStack.matches(last, get(p))) sync(p);
        }
    }

    public static void forget(java.util.UUID id) {
        SENT.remove(id);
        AGAIN.remove(id);
    }

    public static final int LEFT = 0, RIGHT = 1, SHIFT = 2, SHIFT_IN = 3;

    private static void set(ServerPlayer p, ItemStack s) {
        p.setData(WayfarersAtlas.ATLAS_SLOT.get(), s);
    }

    /**
     * A click in the inventory screen.
     * LEFT: swap the slot with the cursor (only an Atlas fits). RIGHT: like a bundle - a page on the cursor goes
     * into the Atlas, an empty cursor takes the last page out. SHIFT: the Atlas goes back into the inventory.
     * SHIFT_IN: shift-click on an Atlas in inventory slot 'slotIdx' moves it into the Atlas slot.
     */
    static void click(ServerPlayer p, int action, int slotIdx) {
        if (!(p.containerMenu instanceof InventoryMenu menu)) return;
        ItemStack carried = menu.getCarried();
        ItemStack inSlot = get(p);
        boolean sound = true, in = true;
        switch (action) {
            case SHIFT_IN -> {
                if (!inSlot.isEmpty() || slotIdx < 0 || slotIdx >= menu.slots.size()) return;
                Slot s = menu.slots.get(slotIdx);
                ItemStack st = s.getItem();
                if (!st.is(WayfarersAtlas.ATLAS.get()) || !s.mayPickup(p)) return;
                set(p, st.copy());
                s.set(ItemStack.EMPTY);
            }
            case SHIFT -> {
                if (inSlot.isEmpty()) return;
                ItemStack c = inSlot.copy();
                if (!p.getInventory().add(c)) return; // no room
                set(p, ItemStack.EMPTY);
                in = false;
            }
            case RIGHT -> {
                if (!inSlot.isEmpty() && Upgrades.fromItem(carried) != null) { // put the page into the Atlas
                    AtlasItem.install(inSlot, carried, p);
                    sound = false; // install plays its own sound
                } else if (!inSlot.isEmpty() && carried.isEmpty() && Upgrades.of(inSlot) != 0) { // take a page out
                    menu.setCarried(AtlasItem.takeLastPage(inSlot, p));
                    sound = false;
                } else if (!swap(p, menu, carried, inSlot)) {
                    return;
                }
            }
            default -> {
                if (!swap(p, menu, carried, inSlot)) return;
                in = !carried.isEmpty();
            }
        }
        menu.broadcastChanges();
        sync(p);
        if (sound) {
            p.level().playSound(null, p.blockPosition(), in ? SoundEvents.ARMOR_EQUIP_LEATHER.value() : SoundEvents.BOOK_PAGE_TURN,
                    SoundSource.PLAYERS, 0.6f, 1.1f);
        }
    }

    private static boolean swap(ServerPlayer p, InventoryMenu menu, ItemStack carried, ItemStack inSlot) {
        if (!carried.isEmpty() && !carried.is(WayfarersAtlas.ATLAS.get())) return false; // only an Atlas fits
        if (carried.isEmpty() && inSlot.isEmpty()) return false;
        set(p, carried.copy());
        menu.setCarried(inSlot.copy());
        return true;
    }

    private AtlasSlot() {}
}

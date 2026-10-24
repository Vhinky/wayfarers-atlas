package com.wayfarersatlas;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class AtlasCheck {
    /** True if the player carries the Atlas in the inventory, off-hand or a Curios slot. */
    public static boolean has(Player player) {
        return upgrades(player) >= 0;
    }

    /**
     * Upgrades of the Atlas the player carries (if there are several, all their upgrades together),
     * or -1 if the player has no Atlas at all.
     */
    public static int upgrades(Player player) {
        int flags = -1;
        for (ItemStack s : all(player)) flags = Math.max(flags, 0) | Upgrades.of(s);
        return flags;
    }

    /**
     * Every Atlas the player carries, in the order that decides whose map is shown:
     * main hand, off-hand, Curios slots, then the rest of the inventory.
     */
    public static List<ItemStack> all(Player player) {
        Item atlas = WayfarersAtlas.ATLAS.get();
        List<ItemStack> out = new ArrayList<>();
        Inventory inv = player.getInventory();
        ItemStack main = inv.getSelected();
        if (main.is(atlas)) out.add(main);
        for (ItemStack s : inv.offhand) if (s.is(atlas)) out.add(s);
        ItemStack slot = AtlasSlot.get(player); // the Atlas slot next to the inventory
        if (slot.is(atlas)) out.add(slot);
        if (ModList.get().isLoaded("curios")) out.addAll(CuriosCompat.stacks(player, atlas));
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (s.is(atlas) && s != main) out.add(s);
        }
        return out;
    }

    /**
     * Upgrades of the Atlas whose map is shown (main hand first, then off-hand, Curios, inventory), or -1 if the
     * player has no Atlas. Pages work only in the Atlas they were put into.
     */
    public static int primaryUpgrades(Player player) {
        List<ItemStack> list = all(player);
        return list.isEmpty() ? -1 : Upgrades.of(list.get(0));
    }

    /** Map id of an Atlas stack ("a_<uuid>"), or null if it has not been given one yet. */
    public static String mapId(ItemStack atlas) {
        UUID id = atlas.get(WayfarersAtlas.MAP_ID.get());
        return id == null ? null : "a_" + id;
    }

    /**
     * Map id of the Atlas whose map is shown: null = no Atlas at all, "" = an Atlas that has not been given
     * its id by the server yet (just crafted).
     */
    public static String primaryId(Player player) {
        List<ItemStack> list = all(player);
        if (list.isEmpty()) return null;
        String id = mapId(list.get(0));
        return id == null ? "" : id;
    }

    private AtlasCheck() {}
}

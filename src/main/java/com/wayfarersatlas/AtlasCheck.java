package com.wayfarersatlas;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

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
        Item atlas = WayfarersAtlas.ATLAS.get();
        int flags = -1;
        Inventory inv = player.getInventory();
        for (ItemStack s : inv.items) if (s.is(atlas)) flags = Math.max(flags, 0) | Upgrades.of(s);
        for (ItemStack s : inv.offhand) if (s.is(atlas)) flags = Math.max(flags, 0) | Upgrades.of(s);
        if (ModList.get().isLoaded("curios")) {
            int c = CuriosCompat.upgrades(player, atlas);
            if (c >= 0) flags = Math.max(flags, 0) | c;
        }
        return flags;
    }

    private AtlasCheck() {}
}

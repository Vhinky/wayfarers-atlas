package com.wayfarersatlas;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.function.Supplier;

/**
 * Atlas upgrades. Each one is a bit in the Atlas' "upgrades" data and has an item that is put into the Atlas
 * (like an item into a bundle) and can be taken back out. New upgrades only need a new constant here.
 */
public enum Upgrades {
    RADAR(1, WayfarersAtlas.RADAR_UPGRADE::get, "upgrade.wayfarers_atlas.radar");

    public final int bit;
    private final Supplier<Item> item;
    public final String nameKey;

    Upgrades(int bit, Supplier<Item> item, String nameKey) {
        this.bit = bit;
        this.item = item;
        this.nameKey = nameKey;
    }

    public Item item() {
        return item.get();
    }

    public static int of(ItemStack atlas) {
        Integer v = atlas.get(WayfarersAtlas.UPGRADES.get());
        return v == null ? 0 : v;
    }

    public boolean in(ItemStack atlas) {
        return (of(atlas) & bit) != 0;
    }

    public boolean in(int flags) {
        return (flags & bit) != 0;
    }

    /** The upgrade a stack is, or null. */
    public static Upgrades fromItem(ItemStack stack) {
        for (Upgrades u : values()) if (stack.is(u.item())) return u;
        return null;
    }
}

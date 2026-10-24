package com.wayfarersatlas;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * An Atlas upgrade. Install it by right-clicking it onto the Atlas in the inventory (or the Atlas onto it),
 * the same way items go into a bundle.
 */
public class UpgradeItem extends Item {
    private final Upgrades upgrade;

    public UpgradeItem(Properties properties, Upgrades upgrade) {
        super(properties);
        this.upgrade = upgrade;
    }

    public Upgrades upgrade() {
        return upgrade;
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    /** The upgrade is held on the cursor and right-clicked onto a slot with the Atlas. */
    @Override
    public boolean overrideStackedOnOther(ItemStack stack, Slot slot, ClickAction action, Player player) {
        if (action != ClickAction.SECONDARY) return false;
        ItemStack atlas = slot.getItem();
        if (!atlas.is(WayfarersAtlas.ATLAS.get()) || !slot.allowModification(player)) return false;
        return AtlasItem.install(atlas, stack, player);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(upgrade.nameKey + ".desc").withStyle(ChatFormatting.GRAY));
    }

    static void sound(Player player, boolean insert) {
        player.playSound(insert ? SoundEvents.BUNDLE_INSERT : SoundEvents.BUNDLE_REMOVE_ONE,
                0.8f, 0.8f + player.level().getRandom().nextFloat() * 0.4f);
    }

    static void fail(Player player) {
        player.playSound(SoundEvents.BUNDLE_DROP_CONTENTS, 0.5f, 1.4f);
    }
}

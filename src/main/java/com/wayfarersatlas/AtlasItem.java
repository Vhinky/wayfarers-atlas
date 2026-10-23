package com.wayfarersatlas;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * The Atlas. Upgrades are kept inside it (like items in a bundle):
 *  - right-click an upgrade onto the Atlas in the inventory (or the Atlas onto an upgrade) to install it;
 *  - right-click the Atlas with an empty cursor to take the last installed upgrade back out.
 */
public class AtlasItem extends Item {
    public AtlasItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            ClientHooks.openMap();
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return Upgrades.of(stack) != 0; // an upgraded Atlas shimmers
    }

    /** Puts one upgrade from 'upgradeStack' into the Atlas. */
    static boolean install(ItemStack atlas, ItemStack upgradeStack, Player player) {
        Upgrades u = Upgrades.fromItem(upgradeStack);
        if (u == null) return false;
        if (u.in(atlas)) { // already there: just a soft "no" sound, nothing moves
            UpgradeItem.fail(player);
            return true;
        }
        atlas.set(WayfarersAtlas.UPGRADES.get(), Upgrades.of(atlas) | u.bit);
        upgradeStack.shrink(1);
        UpgradeItem.sound(player, true);
        return true;
    }

    /** The Atlas is held on the cursor and right-clicked onto a slot with an upgrade. */
    @Override
    public boolean overrideStackedOnOther(ItemStack atlas, Slot slot, ClickAction action, Player player) {
        if (action != ClickAction.SECONDARY) return false;
        ItemStack other = slot.getItem();
        if (Upgrades.fromItem(other) == null || !slot.allowModification(player)) return false;
        boolean r = install(atlas, other, player);
        slot.setChanged();
        return r;
    }

    /** Something on the cursor is right-clicked onto the Atlas. */
    @Override
    public boolean overrideOtherStackedOnMe(ItemStack atlas, ItemStack other, Slot slot, ClickAction action,
                                            Player player, SlotAccess cursor) {
        if (action != ClickAction.SECONDARY || !slot.allowModification(player)) return false;
        if (!other.isEmpty()) return install(atlas, other, player);

        // empty cursor: take the last installed upgrade out
        int flags = Upgrades.of(atlas);
        if (flags == 0) return false; // nothing inside: normal right-click (picks the Atlas up)
        Upgrades[] all = Upgrades.values();
        for (int i = all.length - 1; i >= 0; i--) {
            if (all[i].in(flags)) {
                int rest = flags & ~all[i].bit;
                if (rest == 0) atlas.remove(WayfarersAtlas.UPGRADES.get());
                else atlas.set(WayfarersAtlas.UPGRADES.get(), rest);
                cursor.set(new ItemStack(all[i].item()));
                UpgradeItem.sound(player, false);
                return true;
            }
        }
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.wayfarers_atlas.atlas.tooltip").withStyle(ChatFormatting.GRAY));
        int flags = Upgrades.of(stack);
        if (flags != 0) {
            tooltip.add(Component.translatable("item.wayfarers_atlas.atlas.upgrades").withStyle(ChatFormatting.GOLD));
            for (Upgrades u : Upgrades.values()) {
                if (u.in(flags)) {
                    tooltip.add(Component.literal(" • ").append(Component.translatable(u.nameKey))
                            .withStyle(ChatFormatting.YELLOW));
                }
            }
            tooltip.add(Component.translatable("item.wayfarers_atlas.atlas.take_out").withStyle(ChatFormatting.DARK_GRAY));
        } else {
            tooltip.add(Component.translatable("item.wayfarers_atlas.atlas.no_upgrades").withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}

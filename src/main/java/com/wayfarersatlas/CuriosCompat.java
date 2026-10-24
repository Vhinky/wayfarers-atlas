package com.wayfarersatlas;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Looks into Curios slots through reflection, so the mod needs no compile-time dependency on Curios
 * and still works when Curios is absent.
 */
final class CuriosCompat {
    private static boolean failed = false;

    static boolean has(Player player, Item item) {
        return !stacks(player, item).isEmpty();
    }

    /** Upgrades of the Atlas worn in a Curios slot, or -1 if there is none. */
    static int upgrades(Player player, Item item) {
        int flags = -1;
        for (ItemStack s : stacks(player, item)) flags = Math.max(flags, 0) | Upgrades.of(s);
        return flags;
    }

    /** The stacks of this item worn in Curios slots (the real stacks, changes to them stick). */
    static List<ItemStack> stacks(Player player, Item item) {
        List<ItemStack> out = new ArrayList<>();
        if (failed) return out;
        try {
            Class<?> api = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Object result = api.getMethod("getCuriosInventory", LivingEntity.class).invoke(null, player);
            if (!(result instanceof Optional<?> optional) || optional.isEmpty()) return out;
            Class<?> handlerClass = Class.forName("top.theillusivec4.curios.api.type.capability.ICuriosItemHandler");
            Object equipped = handlerClass.getMethod("getEquippedCurios").invoke(optional.get());
            if (equipped instanceof IItemHandlerModifiable handler) {
                for (int i = 0; i < handler.getSlots(); i++) {
                    ItemStack s = handler.getStackInSlot(i);
                    if (s.is(item)) out.add(s);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            failed = true;
            LogUtils.getLogger().error("Wayfarer's Atlas: could not read Curios slots, falling back to inventory only", e);
        }
        return out;
    }

    private CuriosCompat() {}
}

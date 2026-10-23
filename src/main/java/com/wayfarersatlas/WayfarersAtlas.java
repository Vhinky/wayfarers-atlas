package com.wayfarersatlas;

import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Common part of the mod: the Atlas, its upgrades and their crafting parts (runs on client and server). */
@Mod(WayfarersAtlas.MODID)
public class WayfarersAtlas {
    public static final String MODID = "wayfarers_atlas";

    // --- data stored on the Atlas: installed upgrades as bit flags (see Upgrades) ---
    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, MODID);
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> UPGRADES =
            COMPONENTS.register("upgrades", () -> DataComponentType.<Integer>builder()
                    .persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT)
                    .build());

    // --- items ---
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredItem<Item> ATLAS = ITEMS.registerItem("atlas",
            props -> new AtlasItem(props.stacksTo(1)));
    /** Crafting part: a lens of amethyst in glass. */
    public static final DeferredItem<Item> TRACKER_LENS = ITEMS.registerItem("tracker_lens",
            props -> new TooltipItem(props, "item.wayfarers_atlas.tracker_lens.tooltip"));
    /** Upgrade: shows creatures around you on the maps. */
    public static final DeferredItem<Item> RADAR_UPGRADE = ITEMS.registerItem("radar_upgrade",
            props -> new UpgradeItem(props.stacksTo(16), Upgrades.RADAR));

    // --- own tab in the creative inventory ---
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.wayfarers_atlas"))
            .icon(() -> new ItemStack(ATLAS.get()))
            .withTabsBefore(CreativeModeTabs.SPAWN_EGGS)
            .displayItems((params, out) -> {
                out.accept(ATLAS.get());
                ItemStack withRadar = new ItemStack(ATLAS.get()); // an Atlas with the radar page already inside
                withRadar.set(UPGRADES.get(), Upgrades.RADAR.bit);
                out.accept(withRadar);
                out.accept(RADAR_UPGRADE.get());
                out.accept(TRACKER_LENS.get());
            })
            .build());

    public WayfarersAtlas(IEventBus modBus, ModContainer container) {
        COMPONENTS.register(modBus);
        ITEMS.register(modBus);
        TABS.register(modBus);
    }
}

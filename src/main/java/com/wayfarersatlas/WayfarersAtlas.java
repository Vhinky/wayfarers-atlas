package com.wayfarersatlas;

import com.mojang.serialization.Codec;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredBlock;
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
    /** Which map lives in this Atlas. Given by the server; a new Atlas starts with an empty map. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<java.util.UUID>> MAP_ID =
            COMPONENTS.register("map_id", () -> DataComponentType.<java.util.UUID>builder()
                    .persistent(UUIDUtil.CODEC)
                    .networkSynchronized(UUIDUtil.STREAM_CODEC)
                    .build());

    // --- data kept on the player: the Atlas slot (stays with you when you die) ---
    public static final DeferredRegister<net.neoforged.neoforge.attachment.AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(net.neoforged.neoforge.registries.NeoForgeRegistries.ATTACHMENT_TYPES, MODID);
    public static final java.util.function.Supplier<net.neoforged.neoforge.attachment.AttachmentType<ItemStack>> ATLAS_SLOT =
            ATTACHMENTS.register("atlas_slot", () -> net.neoforged.neoforge.attachment.AttachmentType
                    .builder(() -> ItemStack.EMPTY)
                    .serialize(ItemStack.OPTIONAL_CODEC)
                    .copyOnDeath()
                    .build());

    // --- blocks ---
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    /** Two blocks wide table: share explored chunks (and markers) between Atlases. */
    public static final DeferredBlock<MapTableBlock> MAP_TABLE = BLOCKS.registerBlock("map_table", MapTableBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5f).sound(SoundType.WOOD)
                    .ignitedByLava().noOcclusion().pushReaction(PushReaction.BLOCK));

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
    /** Upgrade: marks where you died on the maps. */
    public static final DeferredItem<Item> DEATH_PAGE = ITEMS.registerItem("death_page",
            props -> new UpgradeItem(props.stacksTo(16), Upgrades.DEATH));
    /** Upgrade: shows items lying on the ground on the maps. */
    public static final DeferredItem<Item> ITEM_PAGE = ITEMS.registerItem("item_page",
            props -> new UpgradeItem(props.stacksTo(16), Upgrades.ITEMS));
    public static final DeferredItem<BlockItem> MAP_TABLE_ITEM = ITEMS.register("map_table",
            () -> new TooltipBlockItem(MAP_TABLE.get(), new Item.Properties(), "block.wayfarers_atlas.map_table.tooltip"));

    // --- own tab in the creative inventory ---
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.wayfarers_atlas"))
            .icon(() -> new ItemStack(ATLAS.get()))
            .withTabsBefore(CreativeModeTabs.SPAWN_EGGS)
            .displayItems((params, out) -> {
                out.accept(ATLAS.get());
                out.accept(RADAR_UPGRADE.get());
                out.accept(DEATH_PAGE.get());
                out.accept(ITEM_PAGE.get());
                out.accept(TRACKER_LENS.get());
                out.accept(MAP_TABLE_ITEM.get());
            })
            .build());

    public WayfarersAtlas(IEventBus modBus, ModContainer container) {
        COMPONENTS.register(modBus);
        ATTACHMENTS.register(modBus);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        TABS.register(modBus);
        modBus.addListener(Net::register);
        modBus.addListener(ServerEvents::onConfigReload);
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, ServerConfig.SPEC);
        NeoForge.EVENT_BUS.addListener(ServerEvents::onLogin);
        NeoForge.EVENT_BUS.addListener(ServerEvents::onRespawn);
        NeoForge.EVENT_BUS.addListener(ServerEvents::onStarting);
        NeoForge.EVENT_BUS.addListener(ServerEvents::onStopping);
        NeoForge.EVENT_BUS.addListener(ServerEvents::onTick);
        NeoForge.EVENT_BUS.addListener(ServerEvents::onSave);
        NeoForge.EVENT_BUS.addListener(ServerEvents::onLogout);
    }
}

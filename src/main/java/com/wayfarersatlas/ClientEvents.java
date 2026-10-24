package com.wayfarersatlas;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.util.regex.Matcher;

public final class ClientEvents {
    private static int tick = 0;
    private static boolean atlas = false;
    private static int upgrades = -1; // upgrades of the carried Atlas, -1 = no Atlas
    private static boolean markerKeyHeld = false;

    /** Upgrades installed in the Atlas whose map is shown (bit flags), or -1 if there is no Atlas. */
    public static int atlasUpgrades() {
        return upgrades;
    }

    /** True while the player carries the Atlas (inventory or Curios slot), or always if that is switched off. */
    public static boolean hasAtlas() {
        return atlas;
    }

    private static boolean canUse(net.minecraft.world.entity.player.Player p) {
        return !AtlasConfig.REQUIRE_ATLAS.get() || AtlasCheck.has(p);
    }

    public static void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            atlas = false;
            return;
        }
        tick++;
        TeleportHelper.tick(mc);
        Deaths.tick(mc);
        Nav.tick(mc);
        if (tick % 5 == 0) Trail.tick(mc);
        if (tick == 1) MapScanner.setMode(AtlasConfig.CAVE_MODE.get(), mc.player);
        if (tick % 10 == 1) {
            upgrades = AtlasCheck.primaryUpgrades(mc.player); // pages of the Atlas that is shown, not of every Atlas
            atlas = !AtlasConfig.REQUIRE_ATLAS.get() || upgrades >= 0;
            AtlasSync.check(mc); // the map of the Atlas you carry
        }
        AtlasSync.flush(4);

        if (atlas) {
            MapScanner.updateCave(mc.level, mc.player);
            int rd = mc.options.getEffectiveRenderDistance(), cfg = AtlasConfig.SCAN_DISTANCE.get();
            MapScanner.tick(mc.level, mc.player, cfg > 0 ? Math.min(cfg, rd) : rd); // render distance, or less
        }

        while (Keys.OPEN_MAP.consumeClick()) {
            if (mc.screen == null) {
                if (canUse(mc.player)) mc.setScreen(new MapScreen());
                else needAtlas(mc);
            }
        }
        while (Keys.ZOOM.consumeClick()) {
            if (atlas) MinimapLayer.cycleZoom();
        }

        // the marker key opens a dialog (icon, name, Save) - once per press, never while held
        boolean clicked = false;
        while (Keys.ADD_MARKER.consumeClick()) clicked = true;
        if (clicked && !markerKeyHeld && mc.screen == null) {
            if (!canUse(mc.player)) {
                needAtlas(mc);
            } else {
                Marker m = new Marker(I18n.get("marker.wayfarers_atlas.default"), MapScanner.dimId(mc.level),
                        mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ(), MarkerScreen.PALETTE[0], 0);
                mc.setScreen(new MarkerScreen(null, m, true));
            }
        }
        markerKeyHeld = Keys.ADD_MARKER.isDown();
    }

    /** Turns a share code in chat into a readable line with a clickable "add marker" button. */
    public static void onChat(ClientChatReceivedEvent e) {
        if (TeleportHelper.isHiddenFeedback(e.getMessage())) { // hide our helper commands (slow falling, jump up)
            e.setCanceled(true);
            return;
        }
        String text = e.getMessage().getString();
        Matcher m = ShareUtil.find(text);
        if (m == null) return;
        Marker mk = ShareUtil.fromMatcher(m);
        if (mk == null) return;
        String code = m.group();
        MutableComponent out = Component.literal(text.substring(0, m.start()));
        out.append(Component.translatable("msg.wayfarers_atlas.shared", mk.name, mk.x, mk.y, mk.z).withStyle(ChatFormatting.GOLD));
        out.append(Component.literal(" "));
        out.append(Component.translatable("msg.wayfarers_atlas.add_marker").withStyle(s -> s
                .withColor(ChatFormatting.GREEN)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/atlasimport " + code))));
        out.append(Component.literal(text.substring(m.end())));
        e.setMessage(out);
    }

    /** Client-side command used by the chat button: /atlasimport <code>. */
    public static void onClientCommands(RegisterClientCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("atlasimport")
                .then(Commands.argument("code", StringArgumentType.greedyString()).executes(ctx -> {
                    Minecraft mc = Minecraft.getInstance();
                    Marker mk = ShareUtil.importCode(StringArgumentType.getString(ctx, "code"));
                    if (mk != null) { // a clear "pling", so you feel the marker was really added
                        mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                                net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 1.5f, 0.7f));
                    }
                    if (mc.player != null) {
                        mc.player.displayClientMessage(mk == null
                                ? Component.translatable("msg.wayfarers_atlas.import_fail")
                                : Component.translatable("msg.wayfarers_atlas.import_ok", mk.name), true);
                    }
                    return mk == null ? 0 : 1;
                })));
    }

    private static void needAtlas(Minecraft mc) {
        mc.player.displayClientMessage(Component.translatable("msg.wayfarers_atlas.need_atlas"), true);
    }

    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn e) {
        tick = 0;
        atlas = false;
        MarkerStore.currentKey = MarkerStore.computeWorldKey();
        MapCache.clear(); // the map comes from the Atlas (the server sends it)
        AtlasSync.reset();
        MarkerStore.MARKERS.clear(); // markers come from the Atlas as well
        MarkerStore.RULERS.clear();
        Deaths.reset();
        Nav.clear();
        MapScreen.resetView();
        Trail.clear();
    }

    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        try {
            AtlasSync.flush(Integer.MAX_VALUE); // last mapped chunks and marker changes into the Atlas
        } catch (RuntimeException ignored) {
        }
        AtlasSync.saveLocalNow(); // the map appears at once when you come back
        AtlasSync.reset();
        ServerRules.reset();
        AtlasSlot.clientStack = net.minecraft.world.item.ItemStack.EMPTY;
        MapCache.clear();
        BlockColorCache.clear();
        MobHeads.clearCache();
        MarkerStore.MARKERS.clear();
        atlas = false;
    }

    private ClientEvents() {}
}

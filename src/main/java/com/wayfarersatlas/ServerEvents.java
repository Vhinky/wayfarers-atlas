package com.wayfarersatlas;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.UUID;

/** Server side: keeps the Atlas maps, gives new Atlases their map id, sends maps to players. */
public final class ServerEvents {
    private static int tick = 0;

    public static void onStarting(ServerStartingEvent e) {
        MapStore.start(e.getServer());
        WaystonesServer.start(e.getServer());
        WorldMarks.start(e.getServer());
        TeamStore.start(e.getServer());
    }

    public static void onStopping(ServerStoppingEvent e) {
        MapStore.stop();
        WaystonesServer.stop();
        WorldMarks.stop();
        TeamStore.stop();
        Reveal.clear();
    }

    public static void onTick(ServerTickEvent.Post e) {
        tick++;
        Net.pump(e.getServer());
        Reveal.tick(e.getServer());
        TeamStore.tick(e.getServer());
        AtlasSlot.tick(e.getServer()); // the slot shown by each player's game is what the server keeps
        if (tick % 20 == 10) WorldMarks.tick(e.getServer()); // secret markers and regions that someone found
        if (tick % 20 == 0) {
            // every Atlas gets its own map id the first time someone carries it
            for (ServerPlayer p : e.getServer().getPlayerList().getPlayers()) {
                for (ItemStack s : AtlasCheck.all(p)) {
                    if (s.get(WayfarersAtlas.MAP_ID.get()) == null) {
                        s.set(WayfarersAtlas.MAP_ID.get(), UUID.randomUUID());
                        if (s == AtlasSlot.get(p)) AtlasSlot.sync(p); // the slot is not a normal inventory slot
                    }
                }
            }
        }
    }

    /** The world is saved (autosave, /save-all, quitting): save the maps with it. */
    public static void onSave(LevelEvent.Save e) {
        if (e.getLevel() instanceof ServerLevel sl && sl.dimension() == Level.OVERWORLD) MapStore.saveAll(true);
    }

    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp) {
            Net.sendRules(sp);
            AtlasSlot.sync(sp);
            WorldMarks.send(sp);
            starterAtlas(sp);
        }
    }

    private static final String STARTER_TAG = "wayfarers_atlas_starter";

    /** Server rule "starterAtlas": an Atlas for everyone who joins this world for the first time. */
    private static void starterAtlas(ServerPlayer sp) {
        if (!ServerConfig.STARTER_ATLAS.get()) return;
        // kept in the part of the player's data that survives death
        net.minecraft.nbt.CompoundTag data = sp.getPersistentData();
        net.minecraft.nbt.CompoundTag kept = data.getCompound(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG);
        if (kept.getBoolean(STARTER_TAG)) return;
        kept.putBoolean(STARTER_TAG, true);
        data.put(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG, kept);
        if (AtlasCheck.has(sp)) return; // already carries one
        ItemStack atlas = new ItemStack(WayfarersAtlas.ATLAS.get());
        if (!sp.getInventory().add(atlas)) sp.drop(atlas, false);
    }

    /** After death the Atlas slot was copied to the new player: show it again. */
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp) AtlasSlot.syncAfterRespawn(sp);
    }

    /** The server config file was changed: tell everyone the new rules. */
    public static void onConfigReload(ModConfigEvent.Reloading e) {
        if (e.getConfig().getSpec() != ServerConfig.SPEC) return;
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        server.execute(() -> {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) Net.sendRules(p);
        });
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        Net.forget(e.getEntity().getUUID());
        AtlasSlot.forget(e.getEntity().getUUID());
        if (e.getEntity() instanceof ServerPlayer sp) TeamStore.logout(sp);
    }

    private ServerEvents() {}
}

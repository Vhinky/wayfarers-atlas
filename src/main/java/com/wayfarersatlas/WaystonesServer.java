package com.wayfarersatlas;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Waystones support, on the server: a destroyed waystone takes its marker out of every Atlas and map table, also
 * those nobody carries right now (they lose it the next time they are opened). The places of destroyed waystones
 * are kept in the world (wayfarers_atlas/waystones_removed.txt); building a waystone there again clears the place.
 */
public final class WaystonesServer {
    private static final String WAYSTONE_TYPE = "waystones:waystone";
    public static final int ICON = 1; // waystone markers: the filled circle
    private static final Set<String> REMOVED = new LinkedHashSet<>();
    private static Path file;
    private static boolean hooked = false;
    private static Method getPos, getDimension, getType, removedWaystone, activatedWaystone;

    /** Hooks into the Waystones events; called once, after every mod is loaded. */
    public static void init() {
        if (!ModList.get().isLoaded("waystones")) return;
        try {
            Class<?> w = Class.forName("net.blay09.mods.waystones.api.Waystone");
            getPos = w.getMethod("getPos");
            getDimension = w.getMethod("getDimension");
            getType = w.getMethod("getWaystoneType");
            Class<?> removed = Class.forName("net.blay09.mods.waystones.api.event.WaystoneRemovedEvent");
            Class<?> activated = Class.forName("net.blay09.mods.waystones.api.event.WaystoneActivatedEvent");
            removedWaystone = removed.getMethod("getWaystone");
            activatedWaystone = activated.getMethod("getWaystone");
            Object events = Class.forName("net.blay09.mods.balm.api.Balm").getMethod("getEvents").invoke(null);
            Method onEvent = Class.forName("net.blay09.mods.balm.api.event.BalmEvents").getMethod("onEvent", Class.class, Consumer.class);
            onEvent.invoke(events, removed, (Consumer<Object>) WaystonesServer::onRemoved);
            onEvent.invoke(events, activated, (Consumer<Object>) WaystonesServer::onActivated);
            hooked = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LogUtils.getLogger().warn("Wayfarer's Atlas: Waystones found, but its markers could not be hooked up", e);
        }
    }

    /** The world starts: read the places of destroyed waystones. */
    public static void start(MinecraftServer server) {
        REMOVED.clear();
        file = server.getWorldPath(LevelResource.ROOT).resolve("wayfarers_atlas").resolve("waystones_removed.txt").normalize();
        if (!hooked || !Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) if (!line.isBlank()) REMOVED.add(line.trim());
        } catch (IOException e) {
            LogUtils.getLogger().warn("Wayfarer's Atlas: could not read {}", file, e);
        }
    }

    public static void stop() {
        REMOVED.clear();
        file = null;
    }

    private static void write() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, REMOVED, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LogUtils.getLogger().warn("Wayfarer's Atlas: could not save {}", file, e);
        }
    }

    private static String key(Object w) throws ReflectiveOperationException {
        BlockPos pos = (BlockPos) getPos.invoke(w);
        ResourceKey<?> dim = (ResourceKey<?>) getDimension.invoke(w);
        return dim.location() + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ();
    }

    private static boolean onServer() {
        MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
        return s != null && s.isSameThread() && file != null; // the client copy of Waystones fires these too
    }

    /** A waystone was destroyed (not moved with silk touch). */
    private static void onRemoved(Object e) {
        if (!onServer()) return;
        try {
            Object w = removedWaystone.invoke(e);
            if (w == null || !WAYSTONE_TYPE.equals(String.valueOf(getType.invoke(w)))) return;
            if (!REMOVED.add(key(w))) return;
            write();
            // Atlases and tables loaded right now lose the marker at once; who carries such an Atlas sees it go
            Set<String> changed = new HashSet<>();
            MapStore.forLoaded(d -> {
                if (strip(d.markers)) {
                    d.dirty = true;
                    changed.add(d.id);
                }
            });
            if (changed.isEmpty()) return;
            for (ServerPlayer p : ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayers()) {
                Set<String> ids = new HashSet<>();
                ids.add("p_" + p.getUUID());
                for (ItemStack s : AtlasCheck.all(p)) {
                    String id = AtlasCheck.mapId(s);
                    if (id != null) ids.add(id);
                }
                for (String id : ids) {
                    if (changed.contains(id)) PacketDistributor.sendToPlayer(p, new Net.AtlasMarkers(id, new ArrayList<>(MapStore.get(id).markers)));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            LogUtils.getLogger().debug("Wayfarer's Atlas: waystone removal skipped", ex);
        }
    }

    /** A waystone was activated: if one stood here before and was destroyed, this place counts again. */
    private static void onActivated(Object e) {
        if (!onServer()) return;
        try {
            Object w = activatedWaystone.invoke(e);
            if (w != null && REMOVED.remove(key(w))) write();
        } catch (ReflectiveOperationException | RuntimeException ex) {
            LogUtils.getLogger().debug("Wayfarer's Atlas: waystone activation skipped", ex);
        }
    }

    /** Is this marker code the marker of a destroyed waystone? */
    public static boolean gone(String code) {
        if (REMOVED.isEmpty() || !code.startsWith("atlas|")) return false;
        String[] p = code.split("\\|", -1);
        if (p.length < 8) return false;
        try {
            if (Integer.parseInt(p[6].trim()) != ICON) return false;
            int x = Integer.parseInt(p[3].trim()), y = Integer.parseInt(p[4].trim()), z = Integer.parseInt(p[5].trim());
            for (int dy = -1; dy <= 1; dy++) if (REMOVED.contains(p[2] + "|" + x + "|" + (y + dy) + "|" + z)) return true;
        } catch (NumberFormatException ignored) {
        }
        return false;
    }

    /** Takes the markers of destroyed waystones out of a list of codes; true if any were there. */
    public static boolean strip(java.util.Collection<String> codes) {
        if (REMOVED.isEmpty()) return false;
        return codes.removeIf(WaystonesServer::gone);
    }

    private WaystonesServer() {}
}

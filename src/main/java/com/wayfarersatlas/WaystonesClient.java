package com.wayfarersatlas;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Waystones support, on the client (works only when Waystones is installed, no hard dependency). It listens to what
 * Waystones tells the client about your waystones:
 * - a waystone you just activated: the Atlas you carry gets a marker there (filled circle, random colour, its name);
 * - a waystone renamed or moved: its marker follows;
 * - a waystone destroyed: its marker is removed.
 * Can be switched off in the settings.
 */
public final class WaystonesClient {
    private static final String WAYSTONE_TYPE = "waystones:waystone";
    private static final int ICON = WaystonesServer.ICON; // filled circle

    private record Known(String dim, int x, int y, int z, String name) {}

    /** Waystones the client knows about (id -> where it stands); null until the first list after joining. */
    private static Map<UUID, Known> known = null;

    private static Method getUid, getName, getPos, getDimension, getType;
    private static Method listType, listWaystones, updWaystone, remType, remId, remDestroyed;

    /** Hooks into the Waystones client events; called once at client setup. */
    public static void init() {
        if (!ModList.get().isLoaded("waystones")) return;
        try {
            Class<?> w = Class.forName("net.blay09.mods.waystones.api.Waystone");
            getUid = w.getMethod("getWaystoneUid");
            getName = w.getMethod("getName");
            getPos = w.getMethod("getPos");
            getDimension = w.getMethod("getDimension");
            getType = w.getMethod("getWaystoneType");
            String ev = "net.blay09.mods.waystones.api.event.";
            Class<?> list = Class.forName(ev + "WaystonesListReceivedEvent");
            Class<?> upd = Class.forName(ev + "WaystoneUpdateReceivedEvent");
            Class<?> rem = Class.forName(ev + "WaystoneRemoveReceivedEvent");
            listType = list.getMethod("getWaystoneType");
            listWaystones = list.getMethod("getWaystones");
            updWaystone = upd.getMethod("getWaystone");
            remType = rem.getMethod("getWaystoneType");
            remId = rem.getMethod("getWaystoneId");
            remDestroyed = rem.getMethod("wasDestroyed");

            Object events = Class.forName("net.blay09.mods.balm.api.Balm").getMethod("getEvents").invoke(null);
            Method onEvent = Class.forName("net.blay09.mods.balm.api.event.BalmEvents").getMethod("onEvent", Class.class, Consumer.class);
            onEvent.invoke(events, list, (Consumer<Object>) e -> later(() -> onList(e)));
            onEvent.invoke(events, upd, (Consumer<Object>) e -> later(() -> onUpdate(e)));
            onEvent.invoke(events, rem, (Consumer<Object>) e -> later(() -> onRemove(e)));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LogUtils.getLogger().warn("Wayfarer's Atlas: Waystones found, but its markers could not be hooked up", e);
        }
    }

    /** Left the world: the next list is the starting point again. */
    public static void reset() {
        known = null;
    }

    private interface Job {
        void run() throws ReflectiveOperationException;
    }

    /** Runs on the game thread, never breaks the game. */
    private static void later(Job job) {
        Minecraft.getInstance().execute(() -> {
            try {
                job.run();
            } catch (ReflectiveOperationException | RuntimeException e) {
                LogUtils.getLogger().debug("Wayfarer's Atlas: waystone marker skipped", e);
            }
        });
    }

    private static Known read(Object w) throws ReflectiveOperationException {
        Object n = getName.invoke(w);
        String name = n instanceof Component c ? c.getString().trim() : "";
        if (name.length() > 32) name = name.substring(0, 32);
        BlockPos pos = (BlockPos) getPos.invoke(w);
        ResourceKey<?> dim = (ResourceKey<?>) getDimension.invoke(w);
        return new Known(dim.location().toString(), pos.getX(), pos.getY(), pos.getZ(), name);
    }

    private static boolean isWaystone(Object type) {
        return WAYSTONE_TYPE.equals(String.valueOf(type)); // not sharestones or warp plates
    }

    /** All your waystones (when you join, and again after each one you activate): new ones get a marker. */
    private static void onList(Object e) throws ReflectiveOperationException {
        if (!isWaystone(listType.invoke(e))) return;
        boolean first = known == null;
        Map<UUID, Known> now = new HashMap<>();
        for (Object w : (List<?>) listWaystones.invoke(e)) {
            if (!isWaystone(getType.invoke(w))) continue;
            UUID id = (UUID) getUid.invoke(w);
            Known k = read(w);
            now.put(id, k);
            if (!first && !known.containsKey(id)) add(k);
        }
        known = now;
    }

    /**
     * Right-click on a waystone you already know (for example with another Atlas in your bag): the Atlas that is
     * shown gets its marker if it does not have it yet. A waystone you activate for the first time gets it from the list.
     */
    public static void onClick(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock e) {
        if (known == null || !e.getLevel().isClientSide() || e.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        BlockPos pos = e.getPos();
        String dim = e.getLevel().dimension().location().toString();
        for (Known k : known.values()) {
            if (k.dim.equals(dim) && k.x == pos.getX() && k.z == pos.getZ() && (pos.getY() == k.y || pos.getY() == k.y + 1)) {
                add(k); // nothing happens if the marker is already there or the setting is off
                return;
            }
        }
    }

    /** A waystone was renamed (or moved with silk touch): its marker follows. */
    private static void onUpdate(Object e) throws ReflectiveOperationException {
        Object w = updWaystone.invoke(e);
        if (!isWaystone(getType.invoke(w))) return;
        UUID id = (UUID) getUid.invoke(w);
        Known k = read(w);
        Known old = known == null ? null : known.get(id);
        if (known != null && old != null) known.put(id, k);
        if (!enabled()) return;
        Marker m = find(old != null ? old : k);
        if (m == null) return;
        boolean changed = false;
        if (!k.name.isEmpty() && !m.name.equals(k.name)) {
            m.name = k.name;
            changed = true;
        }
        if (!m.dim.equals(k.dim) || m.x != k.x || m.y != k.y || m.z != k.z) {
            m.dim = k.dim;
            m.x = k.x;
            m.y = k.y;
            m.z = k.z;
            changed = true;
        }
        if (changed) MarkerStore.save();
    }

    /** A waystone was destroyed: its marker goes too (not when it is only moved with silk touch). */
    private static void onRemove(Object e) throws ReflectiveOperationException {
        if (!isWaystone(remType.invoke(e))) return;
        UUID id = (UUID) remId.invoke(e);
        Known k = known == null ? null : known.get(id);
        if (!Boolean.TRUE.equals(remDestroyed.invoke(e)) || k == null) return;
        known.remove(id);
        if (!enabled()) return;
        Marker m = find(k);
        if (m != null) {
            MarkerStore.MARKERS.remove(m);
            if (Nav.is(m)) Nav.clear();
            MarkerStore.save();
        }
    }

    private static boolean enabled() {
        return AtlasConfig.WAYSTONE_MARKERS.get() && AtlasSync.active() != null && ClientEvents.hasAtlas();
    }

    /** The waystone marker standing at that waystone in the Atlas that is shown. */
    private static Marker find(Known k) {
        for (Marker m : MarkerStore.MARKERS) {
            if (m.icon == ICON && m.dim.equals(k.dim) && m.x == k.x && m.z == k.z && Math.abs(m.y - k.y) <= 1) return m;
        }
        return null;
    }

    private static void add(Known k) {
        if (!enabled() || find(k) != null) return;
        String name = k.name.isEmpty() ? I18n.get("marker.wayfarers_atlas.waystone") : k.name;
        int[] pal = MarkerScreen.PALETTE;
        MarkerStore.MARKERS.add(new Marker(name, k.dim, k.x, k.y, k.z, pal[ThreadLocalRandom.current().nextInt(pal.length)], ICON));
        MarkerStore.save();
    }

    private WaystonesClient() {}
}

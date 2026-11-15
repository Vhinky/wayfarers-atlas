package com.wayfarersatlas;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The travel record of an Atlas (its "Chronicle" page): how far you went with it, how many days, the farthest place,
 * the worlds and the biomes you found (with the day). Kept inside the Atlas like its
 * markers ("chron|..." and "chronb|..." codes), counted while you carry it.
 */
public final class Chronicle {
    private static final long SAVE_MS = 60_000;

    private static boolean loaded = false; // the record of the Atlas shown has arrived (or it has none yet)
    static long startDay = -1;            // game day the record started (-1: not yet)
    static double walked = 0;             // blocks
    static int far = 0;                   // blocks from where the record started (same world)
    static int deaths = 0;
    private static int sx, sz;            // where it started
    private static String sdim = "";
    static final Set<String> DIMS = new LinkedHashSet<>();
    static final Map<String, Long> BIOMES = new LinkedHashMap<>(); // biome id -> day found

    private static boolean dirty = false;
    private static long lastSave = 0;
    private static boolean hasLast = false;
    private static double lx, lz;
    private static String ldim = "";
    private static int tick = 0;

    public static void reset() {
        loaded = false;
        startDay = -1;
        walked = 0;
        far = 0;
        deaths = 0;
        sx = sz = 0;
        sdim = "";
        DIMS.clear();
        BIOMES.clear();
        dirty = false;
        hasLast = false;
    }

    /** The codes of the Atlas that is shown (its markers have arrived). */
    public static void load(List<String> codes) {
        if (loaded) return; // already counting (the list came again)
        for (String c : codes) {
            try {
                if (c.startsWith("chron|")) {
                    for (String kv : c.substring(6).split(";")) {
                        int eq = kv.indexOf('=');
                        if (eq < 0) continue;
                        String k = kv.substring(0, eq), v = kv.substring(eq + 1);
                        switch (k) {
                            case "start" -> startDay = Long.parseLong(v);
                            case "walk" -> walked = Double.parseDouble(v);
                            case "far" -> far = Integer.parseInt(v);
                            case "deaths" -> deaths = Integer.parseInt(v);
                            case "sx" -> sx = Integer.parseInt(v);
                            case "sz" -> sz = Integer.parseInt(v);
                            case "sd" -> sdim = v;
                            case "dims" -> {
                                for (String d : v.split(",")) if (!d.isEmpty()) DIMS.add(d);
                            }
                            default -> {}
                        }
                    }
                } else if (c.startsWith("chronb|")) {
                    for (String b : c.substring(7).split(",")) {
                        int at = b.lastIndexOf('@');
                        if (at > 0) BIOMES.putIfAbsent(b.substring(0, at), Long.parseLong(b.substring(at + 1)));
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        loaded = true;
    }

    /** What goes into the Atlas. */
    public static List<String> codes() {
        List<String> out = new ArrayList<>();
        if (!loaded || startDay < 0) return out;
        StringBuilder sb = new StringBuilder("chron|start=").append(startDay)
                .append(";walk=").append(Math.round(walked))
                .append(";far=").append(far)
                .append(";deaths=").append(deaths)
                .append(";sx=").append(sx).append(";sz=").append(sz).append(";sd=").append(sdim);
        sb.append(";dims=").append(String.join(",", DIMS));
        if (sb.length() < 1990) out.add(sb.toString());
        StringBuilder b = new StringBuilder("chronb|");
        for (var e : BIOMES.entrySet()) {
            String item = e.getKey() + "@" + e.getValue();
            if (b.length() + item.length() + 1 > 1990) {
                out.add(b.toString());
                b = new StringBuilder("chronb|");
            }
            if (b.length() > 7) b.append(',');
            b.append(item);
        }
        if (b.length() > 7) out.add(b.toString());
        return out;
    }

    public static void tick(Minecraft mc) {
        if (!loaded || !AtlasSync.markersLoaded() || AtlasSync.active() == null || !ClientEvents.hasAtlas()
                || mc.player.isDeadOrDying() || mc.player.isSpectator()) {
            hasLast = false;
            return;
        }
        long day = day(mc);
        String dim = MapScanner.dimId(mc.level);
        double x = mc.player.getX(), z = mc.player.getZ();
        if (startDay < 0) { // a new record: starts here and now
            startDay = day;
            sx = (int) Math.floor(x);
            sz = (int) Math.floor(z);
            sdim = dim;
            dirty = true;
        }
        if (hasLast && dim.equals(ldim)) {
            double d = Math.hypot(x - lx, z - lz);
            if (d > 0.01 && d < 12) { // not a teleport
                walked += d;
                dirty = true;
            }
        }
        lx = x;
        lz = z;
        ldim = dim;
        hasLast = true;
        if (++tick % 20 == 0) {
            if (DIMS.add(dim)) dirty = true;
            if (dim.equals(sdim)) far = Math.max(far, (int) Math.hypot(x - sx, z - sz));
            String biome = BiomeNames.at(mc.level, mc.player.blockPosition());
            if (!biome.isEmpty() && !BIOMES.containsKey(biome)) {
                BIOMES.put(biome, day);
                dirty = true;
                lastSave = 0; // something new: into the Atlas soon
            }
        }
        if (dirty && Util.getMillis() - lastSave > SAVE_MS) flush();
    }

    public static void onDeath() {
        if (!loaded || startDay < 0) return;
        deaths++;
        dirty = true;
    }

    /** What changed goes into the Atlas now. */
    public static void flush() {
        if (!dirty) return;
        dirty = false;
        lastSave = Util.getMillis();
        MarkerStore.save();
    }

    static long day(Minecraft mc) {
        return mc.level == null ? 0 : mc.level.getDayTime() / 24000L;
    }

    /** Days since the record started (the first one counts). */
    static long days(Minecraft mc) {
        return startDay < 0 ? 0 : Math.max(1, day(mc) - startDay + 1);
    }

    static boolean started() {
        return startDay >= 0;
    }

    /** All biomes the game knows (of every world and mod). */
    static int biomeCount(Minecraft mc) {
        try {
            return mc.level.registryAccess().registryOrThrow(Registries.BIOME).size();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Worlds of the server. */
    static int worldCount(Minecraft mc) {
        return mc.getConnection() == null ? 0 : mc.getConnection().levels().size();
    }

    /** Chunks you mapped yourself (on the surface of every world). */
    static int chunks() {
        int n = 0;
        for (String k : MapCache.keys()) {
            if (k.contains("#")) continue; // cave layers: the same land
            var m = MapCache.chunks(k);
            if (m == null) continue;
            for (int[][] l : m.values()) if (!MapCache.isFaded(l) && MapCache.isOwn(l)) n++;
        }
        return n;
    }

    /** Biomes seen on the map (also those not walked in): id -> -1 if not found yet. */
    static void addMapBiomes(Map<String, Long> into) {
        Set<String> seen = new java.util.HashSet<>();
        for (String k : MapCache.keys()) {
            if (k.contains("#")) continue;
            var m = MapCache.chunks(k);
            if (m == null) continue;
            for (int[][] l : m.values()) {
                if (l.length <= 6 || l[6] == null || MapCache.isFaded(l)) continue;
                for (int b : l[6]) if (b > 0) seen.add(BiomeNames.id(b));
            }
        }
        for (String id : seen) if (!id.isEmpty()) into.putIfAbsent(id, -1L);
    }

    private Chronicle() {}
}

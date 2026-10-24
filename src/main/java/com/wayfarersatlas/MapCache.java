package com.wayfarersatlas;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.ChunkPos;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Top-down data per view key (dimension, or dimension + cave layer).
 * Per chunk: [0] 16x16 colours (ABGR), [1..4] the same sampled down by 2/4/8/16 (point-sampled, so zoomed-out
 * maps stay sharp like Xaero's), [5] surface heights, [6] biome per 4x4 blocks (16 numbers, see BiomeNames),
 * [7..11] block light (0..15, torches, lava...) at the same 5 detail levels - used for the night view,
 * [12] non-null if the chunk came from a map table and you have not been there yourself (drawn faded).
 * This is the map of the Atlas you carry; the server keeps it inside the Atlas (see AtlasSync).
 */
public final class MapCache {
    private static final Map<String, Long2ObjectOpenHashMap<int[][]>> DIMS = new HashMap<>();

    /** Bumped when whole layers appear or vanish (load, clear, dropped slices): views redraw everything. */
    public static int version = 0;

    // Change log: every chunk that was (re)scanned, so views only redraw what changed instead of the whole map.
    private static final int LOG = 16384;
    private static final String[] LOG_KEY = new String[LOG];
    private static final long[] LOG_CHUNK = new long[LOG];
    private static long seq = 0;

    public static long seq() {
        return seq;
    }

    /**
     * Calls out for every chunk of this layer changed since 'since'. Returns false if the log no longer goes back
     * that far (then the caller has to redraw everything).
     */
    public static boolean changesSince(String key, long since, java.util.function.LongConsumer out) {
        if (seq - since > LOG) return false;
        for (long s = since; s < seq; s++) {
            int i = (int) (s % LOG);
            if (key.equals(LOG_KEY[i])) out.accept(LOG_CHUNK[i]);
        }
        return true;
    }

    private static final ArrayDeque<String> MANUAL = new ArrayDeque<>();

    /** Remembers the most recent manual cave slices (the slider); older ones are dropped to save memory. */
    public static void touchManual(String key) {
        if (!key.contains("#m")) return;
        MANUAL.remove(key);
        MANUAL.addLast(key);
        while (MANUAL.size() > 24) {
            DIMS.remove(MANUAL.pollFirst());
            version++;
        }
    }

    public static void dropManual() {
        for (String k : MANUAL) DIMS.remove(k);
        MANUAL.clear();
        version++;
    }

    /** Every layer (dimension / cave slice) that has chunks. */
    public static List<String> keys() {
        return new ArrayList<>(DIMS.keySet());
    }

    public static Long2ObjectOpenHashMap<int[][]> chunks(String key) {
        return DIMS.get(key);
    }

    public static boolean has(String key, int cx, int cz) {
        var m = DIMS.get(key);
        return m != null && m.containsKey(ChunkPos.asLong(cx, cz));
    }

    private static final int[] FADED = {1};
    private static final int[] STALE = {2};

    /** True for a chunk that came from a map table and was not visited yet. */
    public static boolean isFaded(int[][] lods) {
        return lods.length > 12 && lods[12] == FADED;
    }

    /** Mapped by you on this very layer (not seen on a table, not borrowed from a neighbouring cave layer). */
    public static boolean isOwn(int[][] lods) {
        return lods.length <= 12 || lods[12] == null;
    }

    /**
     * Cave view moved to another height slice: until the scanner gets there, the new slice borrows the chunks of the
     * old one around you, so the map never goes blank (like Xaero's). Borrowed chunks are re-scanned first and are
     * never sent to the Atlas.
     */
    public static void borrow(String from, String to, int pcx, int pcz, int radius) {
        var src = DIMS.get(from);
        if (src == null || src.isEmpty()) return;
        var dst = DIMS.computeIfAbsent(to, k -> new Long2ObjectOpenHashMap<>());
        boolean any = false;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                long id = ChunkPos.asLong(pcx + dx, pcz + dz);
                if (dst.containsKey(id)) continue;
                int[][] l = src.get(id);
                if (l == null) continue;
                int[][] copy = new int[13][];
                System.arraycopy(l, 0, copy, 0, Math.min(l.length, 12));
                copy[12] = STALE;
                dst.put(id, copy);
                any = true;
            }
        }
        if (any) version++;
    }

    /** Mapped by yourself (not only seen on a map table). */
    public static boolean hasOwn(String key, int cx, int cz) {
        var m = DIMS.get(key);
        if (m == null) return false;
        int[][] l = m.get(ChunkPos.asLong(cx, cz));
        return l != null && isOwn(l);
    }

    private static boolean same(int[][] old, int[] colors, int[] heights, int[] biomes, int[] light) {
        return Arrays.equals(old[0], colors) && Arrays.equals(old[5], heights)
                && Arrays.equals(old[6], biomes) && Arrays.equals(old[7], light);
    }

    private static void logChange(String key, long id) {
        int i = (int) (seq % LOG);
        LOG_KEY[i] = key;
        LOG_CHUNK[i] = id;
        seq++;
    }

    /** A chunk you just mapped yourself. It also goes to the server, into the Atlas. */
    public static void put(String key, int cx, int cz, int[] colors, int[] heights, int[] biomes, int[] light) {
        var map = DIMS.computeIfAbsent(key, k -> new Long2ObjectOpenHashMap<>());
        long id = ChunkPos.asLong(cx, cz);
        int[][] old = map.get(id);
        if (old != null && isOwn(old) && same(old, colors, heights, biomes, light)) return;
        map.put(id, build(colors, heights, biomes, light, false));
        logChange(key, id);
        AtlasSync.changed(key, id);
    }

    /** A chunk that came from the server (the Atlas' own map, or a map table). What you mapped yourself wins. */
    public static void putIncoming(String key, long id, int[] colors, int[] heights, int[] biomes, int[] light, boolean faded) {
        var map = DIMS.computeIfAbsent(key, k -> new Long2ObjectOpenHashMap<>());
        int[][] old = map.get(id);
        if (old != null && isOwn(old)) return;
        if (old != null && faded && same(old, colors, heights, biomes, light)) return;
        map.put(id, build(colors, heights, biomes, light, faded));
        logChange(key, id);
    }

    static int[][] build(int[] l0, int[] heights, int[] biomes, int[] light, boolean faded) {
        int[][] lods = new int[13][];
        lods[12] = faded ? FADED : null;
        lods[0] = l0;
        lods[7] = light;
        for (int k = 1; k < 5; k++) {
            lods[k] = sample(l0, k);
            lods[7 + k] = sample(light, k);
        }
        lods[5] = heights;
        lods[6] = biomes;
        return lods;
    }

    /** Point-sampled smaller version of a 16x16 array (keeps it sharp). */
    private static int[] sample(int[] l0, int k) {
        int n = 16 >> k;
        int off = (1 << k) >> 1;
        int[] cur = new int[n * n];
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                cur[z * n + x] = l0[(((z << k) + off) << 4) + (x << k) + off];
            }
        }
        return cur;
    }

    /** Y of the top block of a column on the surface layer, or Integer.MIN_VALUE if unknown. */
    public static int heightAt(String dim, int x, int z) {
        var m = DIMS.get(dim);
        if (m == null) return Integer.MIN_VALUE;
        int[][] c = m.get(ChunkPos.asLong(x >> 4, z >> 4));
        return c == null ? Integer.MIN_VALUE : c[5][((z & 15) << 4) | (x & 15)];
    }

    /** Biome id at a block on a layer, or "" if that spot has not been mapped. */
    public static String biomeAt(String key, int x, int z) {
        var m = DIMS.get(key);
        if (m == null) return "";
        int[][] c = m.get(ChunkPos.asLong(x >> 4, z >> 4));
        if (c == null) return "";
        return BiomeNames.id(c[6][(((z & 15) >> 2) << 2) | ((x & 15) >> 2)]);
    }

    public static void clear() {
        DIMS.clear();
        MANUAL.clear();
        version++;
    }

    public static void save(String name) {
        try {
            Files.createDirectories(MarkerStore.dir());
            Path file = MarkerStore.dir().resolve(name + ".map8.gz");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                    new GZIPOutputStream(Files.newOutputStream(file))))) {
                List<String> table = new ArrayList<>();
                for (int i = 0; ; i++) {
                    String id = BiomeNames.id(i);
                    if (i > 0 && id.isEmpty()) break;
                    table.add(id);
                }
                out.writeInt(table.size());
                for (String t : table) out.writeUTF(t);
                List<Map.Entry<String, Long2ObjectOpenHashMap<int[][]>>> keep = new ArrayList<>();
                for (var d : DIMS.entrySet()) if (!d.getKey().contains("#m")) keep.add(d); // manual slices are temporary
                out.writeInt(keep.size());
                for (var d : keep) {
                    out.writeUTF(d.getKey());
                    out.writeInt(d.getValue().size());
                    for (Long2ObjectMap.Entry<int[][]> e : d.getValue().long2ObjectEntrySet()) {
                        out.writeLong(e.getLongKey());
                        for (int c : e.getValue()[0]) out.writeInt(c);
                        for (int h : e.getValue()[5]) out.writeInt(h);
                        for (int b : e.getValue()[6]) out.writeShort(b);
                        for (int l : e.getValue()[7]) out.writeByte(l);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** Map files kept on this computer by older versions (before the map moved into the Atlas). */
    public static Path[] legacyFiles(String name) {
        return new Path[]{MarkerStore.dir().resolve(name + ".map8.gz"), MarkerStore.dir().resolve(name + ".map7.gz")};
    }

    public static void load(String name) {
        DIMS.clear();
        version++;
        Path file = MarkerStore.dir().resolve(name + ".map8.gz");
        boolean hasLight = true;
        if (!Files.exists(file)) { // map from the previous version: keep it, the light fills in as you explore
            file = MarkerStore.dir().resolve(name + ".map7.gz");
            hasLight = false;
        }
        if (!Files.exists(file)) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(file))))) {
            int tn = in.readInt();
            int[] remap = new int[tn];
            for (int i = 0; i < tn; i++) remap[i] = BiomeNames.index(in.readUTF());
            int dims = in.readInt();
            for (int i = 0; i < dims; i++) {
                String key = in.readUTF();
                int n = in.readInt();
                var map = new Long2ObjectOpenHashMap<int[][]>(n);
                for (int j = 0; j < n; j++) {
                    long id = in.readLong();
                    int[] c = new int[256];
                    int[] h = new int[256];
                    for (int p = 0; p < 256; p++) c[p] = in.readInt();
                    for (int p = 0; p < 256; p++) h[p] = in.readInt();
                    int[] b = new int[16];
                    for (int p = 0; p < 16; p++) {
                        int v = in.readShort();
                        b[p] = v >= 0 && v < tn ? remap[v] : 0;
                    }
                    int[] l = new int[256];
                    if (hasLight) for (int p = 0; p < 256; p++) l[p] = in.readByte() & 0xFF;
                    map.put(id, build(c, h, b, l, false));
                }
                DIMS.put(key, map);
            }
        } catch (Exception e) {
            e.printStackTrace();
            DIMS.clear();
        }
        version++;
    }

    private MapCache() {}
}

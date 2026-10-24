package com.wayfarersatlas;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Server side: the maps that live inside the Atlases ("a_<uuid>"), the personal map used when the Atlas is not
 * required ("p_<player uuid>") and the records kept on map tables ("t_<dimension>_<x>_<y>_<z>").
 * Stored in the world folder: <world>/wayfarers_atlas/<id>.map.gz. Loaded when needed, saved with the world
 * (in the background, the game does not stutter) and dropped from memory when nobody used them for a while.
 */
public final class MapStore {
    private static final int MAGIC = 0x57414D31; // "WAM1"
    private static final int FORMAT = 1;
    private static final long IDLE_MS = 10 * 60 * 1000L;

    /** One mapped chunk. Never changed after creation, so it can be shared between maps and save snapshots. */
    public static final class Rec {
        public final int[] colors, heights, biomes; // biomes: indices into the server biome palette
        public final byte[] light;
        public final boolean faded;                  // came from a table: the Atlas owner has not been there

        public Rec(int[] colors, int[] heights, int[] biomes, byte[] light, boolean faded) {
            this.colors = colors;
            this.heights = heights;
            this.biomes = biomes;
            this.light = light;
            this.faded = faded;
        }

        Rec withFaded(boolean f) {
            return f == faded ? this : new Rec(colors, heights, biomes, light, f);
        }

        boolean sameData(Rec o) {
            return Arrays.equals(colors, o.colors) && Arrays.equals(heights, o.heights)
                    && Arrays.equals(biomes, o.biomes) && Arrays.equals(light, o.light);
        }
    }

    public static final class Data {
        final String id;
        final Map<String, Long2ObjectOpenHashMap<Rec>> layers = new HashMap<>();
        final LinkedHashSet<String> markers = new LinkedHashSet<>(); // share codes left on a table
        boolean dirty;
        long lastUse;

        Data(String id) {
            this.id = id;
        }

        Long2ObjectOpenHashMap<Rec> layer(String key) {
            return layers.computeIfAbsent(key, k -> new Long2ObjectOpenHashMap<>());
        }

        void touch() {
            lastUse = System.currentTimeMillis();
        }
    }

    // biome ids are kept as numbers; the names are written with every file
    private static final List<String> PALETTE = new ArrayList<>();
    private static final Object2IntOpenHashMap<String> PAL = new Object2IntOpenHashMap<>();

    static {
        PAL.defaultReturnValue(-1);
        biome("");
    }

    public static int biome(String name) {
        int i = PAL.getInt(name);
        if (i < 0) {
            i = PALETTE.size();
            PALETTE.add(name);
            PAL.put(name, i);
        }
        return i;
    }

    public static String biomeName(int i) {
        return i >= 0 && i < PALETTE.size() ? PALETTE.get(i) : "";
    }

    private static final Map<String, Data> LOADED = new HashMap<>();
    private static Path dir;
    private static ExecutorService io;

    public static void start(MinecraftServer server) {
        dir = server.getWorldPath(LevelResource.ROOT).resolve("wayfarers_atlas").normalize();
        LOADED.clear();
        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Wayfarer's Atlas map saving");
            t.setDaemon(true);
            return t;
        });
    }

    public static void stop() {
        if (dir == null) return;
        saveAll(false);
        io.shutdown();
        try {
            io.awaitTermination(60, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        LOADED.clear();
        dir = null;
    }

    public static boolean running() {
        return dir != null;
    }

    private static Path file(String id) {
        return dir.resolve(id.replaceAll("[^a-zA-Z0-9_.-]", "_") + ".map.gz");
    }

    public static Data get(String id) {
        Data d = LOADED.get(id);
        if (d == null) {
            d = load(id);
            LOADED.put(id, d);
        }
        d.touch();
        return d;
    }

    /** A table was broken: its records are gone. */
    public static void delete(String id) {
        if (dir == null) return;
        LOADED.remove(id);
        Path f = file(id);
        io.execute(() -> {
            try {
                Files.deleteIfExists(f);
            } catch (IOException e) {
                LogUtils.getLogger().warn("Wayfarer's Atlas: could not delete {}", f, e);
            }
        });
    }

    /** Writes every changed map (in the background) and forgets maps nobody used for a while. */
    public static void saveAll(boolean unloadIdle) {
        if (dir == null) return;
        long now = System.currentTimeMillis();
        List<String> palette = new ArrayList<>(PALETTE);
        for (Iterator<Data> it = LOADED.values().iterator(); it.hasNext(); ) {
            Data d = it.next();
            if (d.dirty) {
                d.dirty = false;
                Map<String, Long2ObjectOpenHashMap<Rec>> snap = new HashMap<>();
                for (var e : d.layers.entrySet()) snap.put(e.getKey(), new Long2ObjectOpenHashMap<>(e.getValue()));
                List<String> markers = new ArrayList<>(d.markers);
                Path f = file(d.id);
                io.execute(() -> write(f, palette, markers, snap));
            } else if (unloadIdle && now - d.lastUse > IDLE_MS) {
                it.remove();
            }
        }
    }

    private static void write(Path f, List<String> palette, List<String> markers,
                              Map<String, Long2ObjectOpenHashMap<Rec>> layers) {
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                    new GZIPOutputStream(Files.newOutputStream(tmp))))) {
                out.writeInt(MAGIC);
                out.writeInt(FORMAT);
                out.writeInt(palette.size());
                for (String p : palette) out.writeUTF(p);
                out.writeInt(markers.size());
                for (String m : markers) out.writeUTF(m);
                out.writeInt(layers.size());
                for (var layer : layers.entrySet()) {
                    out.writeUTF(layer.getKey());
                    out.writeInt(layer.getValue().size());
                    for (Long2ObjectMap.Entry<Rec> e : layer.getValue().long2ObjectEntrySet()) {
                        Rec r = e.getValue();
                        out.writeLong(e.getLongKey());
                        out.writeBoolean(r.faded);
                        for (int c : r.colors) out.writeInt(c);
                        for (int h : r.heights) out.writeInt(h);
                        for (int b : r.biomes) out.writeShort(b);
                        out.write(r.light);
                    }
                }
            }
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LogUtils.getLogger().error("Wayfarer's Atlas: could not save {}", f, e);
        }
    }

    private static Data load(String id) {
        Data d = new Data(id);
        Path f = file(id);
        if (!Files.exists(f)) return d;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(f))))) {
            if (in.readInt() != MAGIC) throw new IOException("not a map file");
            in.readInt(); // format
            int pn = in.readInt();
            int[] remap = new int[pn];
            for (int i = 0; i < pn; i++) remap[i] = biome(in.readUTF());
            int mn = in.readInt();
            for (int i = 0; i < mn; i++) d.markers.add(in.readUTF());
            int ln = in.readInt();
            for (int i = 0; i < ln; i++) {
                String key = in.readUTF();
                int n = in.readInt();
                Long2ObjectOpenHashMap<Rec> layer = new Long2ObjectOpenHashMap<>(n);
                for (int j = 0; j < n; j++) {
                    long pos = in.readLong();
                    boolean faded = in.readBoolean();
                    int[] c = new int[256], h = new int[256], b = new int[16];
                    byte[] l = new byte[256];
                    for (int p = 0; p < 256; p++) c[p] = in.readInt();
                    for (int p = 0; p < 256; p++) h[p] = in.readInt();
                    for (int p = 0; p < 16; p++) {
                        int v = in.readShort();
                        b[p] = v >= 0 && v < pn ? remap[v] : 0;
                    }
                    in.readFully(l);
                    layer.put(pos, new Rec(c, h, b, l, faded));
                }
                d.layers.put(key, layer);
            }
        } catch (Exception e) {
            LogUtils.getLogger().error("Wayfarer's Atlas: could not read {}", f, e);
        }
        return d;
    }

    private MapStore() {}
}

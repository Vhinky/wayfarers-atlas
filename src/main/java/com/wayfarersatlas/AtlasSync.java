package com.wayfarersatlas;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Client side of "the map lives in the Atlas". The shown map is the one of the Atlas you carry (main hand first,
 * then off-hand, Curios, inventory). Picking up another Atlas loads its map from the server; what you map is sent
 * to the server and kept inside that Atlas, so whoever carries it next sees the same map.
 */
public final class AtlasSync {
    private static final int CHUNKS_PER_PACKET = 8; // client -> server messages must stay under 32 KB
    private static String active = null;
    private static boolean loading = false;
    private static int received = 0;
    private static final Map<String, LongLinkedOpenHashSet> QUEUE = new LinkedHashMap<>();
    private static boolean markersDirty = false;

    /** Map id that is shown right now (null before the first Atlas). */
    public static String active() {
        return active;
    }

    public static boolean loading() {
        return loading;
    }

    /** Called a few times per second with the id of the Atlas that should be shown. */
    public static void check(Minecraft mc) {
        String id = AtlasCheck.primaryId(mc.player);
        if (id == null && !AtlasConfig.REQUIRE_ATLAS.get()) id = "p_" + mc.player.getUUID(); // personal map
        if (id == null || id.isEmpty() || id.equals(active)) return; // no Atlas / not given an id yet / same map
        flush(Integer.MAX_VALUE); // what was mapped (and marked) with the previous Atlas still goes into it
        saveLocal();
        QUEUE.clear();
        MarkerStore.MARKERS.clear(); // markers (and rulers) belong to the Atlas too: the server sends this one's
        MarkerStore.RULERS.clear();
        markersDirty = false;
        active = id;
        loading = true;
        received = 0;
        MapCache.clear();
        // the local copy is read in the background (no stutter) and shown as soon as it is ready;
        // the server meanwhile sends what changed
        java.nio.file.Path file = localFile(id);
        final String want = id;
        IO.execute(() -> {
            MapCache.Loaded loaded = MapCache.readLocal(file);
            if (loaded != null) Minecraft.getInstance().execute(() -> {
                if (want.equals(active)) MapCache.mergeLocal(loaded);
            });
        });
        PacketDistributor.sendToServer(new Net.RequestMap(id));
    }

    /** Local copy of an Atlas map on this computer, per world / server. */
    private static java.nio.file.Path localFile(String id) {
        return MarkerStore.dir().resolve("cache").resolve(MarkerStore.currentKey).resolve(id + ".map.gz");
    }

    private static final java.util.concurrent.ExecutorService IO = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Wayfarer's Atlas local map");
        t.setDaemon(true);
        return t;
    });

    /** Keeps a copy of the map that is shown, so it appears instantly next time (written in the background). */
    public static void saveLocal() {
        if (active == null) return;
        var snap = MapCache.snapshot();
        var table = MapCache.biomeTable();
        java.nio.file.Path file = localFile(active);
        IO.execute(() -> MapCache.writeLocal(file, snap, table));
    }

    /** Same, but waits until it is written (leaving the world: the game may close right after). */
    public static void saveLocalNow() {
        if (active == null) return;
        var snap = MapCache.snapshot();
        var table = MapCache.biomeTable();
        java.nio.file.Path file = localFile(active);
        try {
            IO.submit(() -> MapCache.writeLocal(file, snap, table)).get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** A chunk was (re)mapped by you: remember to send it. */
    public static void changed(String key, long pos) {
        if (active == null || key.contains("#m")) return; // manual cave slices are temporary
        QUEUE.computeIfAbsent(key, k -> new LongLinkedOpenHashSet()).add(pos);
    }

    /** Sends up to maxPackets messages of mapped chunks to the server. */
    public static void flush(int maxPackets) {
        sendMarkers();
        if (active == null || QUEUE.isEmpty() || Minecraft.getInstance().getConnection() == null) return;
        int sent = 0;
        Net.Batch b = new Net.Batch();
        for (Iterator<Map.Entry<String, LongLinkedOpenHashSet>> it = QUEUE.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            var chunks = MapCache.chunks(e.getKey());
            LongIterator li = e.getValue().iterator();
            while (li.hasNext()) {
                long pos = li.nextLong();
                li.remove();
                int[][] l = chunks == null ? null : chunks.get(pos);
                if (l == null || !MapCache.isOwn(l)) continue;
                int[] bio = new int[16];
                for (int i = 0; i < 16; i++) bio[i] = b.biome(BiomeNames.id(l[6][i]));
                byte[] light = new byte[256];
                for (int i = 0; i < 256; i++) light[i] = (byte) l[7][i];
                b.chunks.add(new Net.Chunk(b.key(e.getKey()), pos, false, l[0], l[5], bio, light));
                if (b.chunks.size() >= CHUNKS_PER_PACKET) {
                    PacketDistributor.sendToServer(new Net.Upload(active, b));
                    b = new Net.Batch();
                    if (++sent >= maxPackets) return;
                }
            }
            it.remove();
        }
        if (!b.chunks.isEmpty()) PacketDistributor.sendToServer(new Net.Upload(active, b));
    }

    /** A marker was added, edited or deleted: the Atlas gets the new list on the next tick. */
    public static void markersChanged() {
        markersDirty = true;
    }

    private static void sendMarkers() {
        if (!markersDirty || active == null || Minecraft.getInstance().getConnection() == null) return;
        markersDirty = false;
        List<String> part = new ArrayList<>();
        int bytes = 0;
        boolean first = true;
        List<String> codes = new ArrayList<>();
        for (Marker m : MarkerStore.MARKERS) codes.add(ShareUtil.encode(m) + (m.death ? "|d" : ""));
        for (MarkerStore.Ruler r : MarkerStore.RULERS) codes.add(r.encode());
        for (String c : codes) {
            int size = c.length() * 3 + 3;
            if (!part.isEmpty() && (bytes + size > 28000 || part.size() >= 1000)) { // stay under the 32 KB limit
                PacketDistributor.sendToServer(new Net.SetMarkers(active, first, false, part));
                first = false;
                part = new ArrayList<>();
                bytes = 0;
            }
            part.add(c);
            bytes += size;
        }
        PacketDistributor.sendToServer(new Net.SetMarkers(active, first, true, part));
    }

    private static boolean sameMarker(Marker a, Marker b) {
        return a.dim.equals(b.dim) && a.x == b.x && a.z == b.z && a.name.equals(b.name);
    }

    private static void addNew(List<Marker> into, List<Marker> from) {
        for (Marker m : from) {
            boolean dup = false;
            for (Marker e : into) {
                if (sameMarker(e, m)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) into.add(m);
        }
    }

    /** The markers kept inside the Atlas that is shown now. */
    public static void onMarkers(Net.AtlasMarkers p) {
        if (!p.atlas().equals(active)) return;
        List<Marker> list = new ArrayList<>();
        List<MarkerStore.Ruler> rulers = new ArrayList<>();
        for (String code : p.markers()) {
            if (code.startsWith("ruler|")) {
                MarkerStore.Ruler r = MarkerStore.Ruler.decode(code);
                if (r != null) rulers.add(r);
                continue;
            }
            Matcher m = ShareUtil.find(code);
            Marker mk = m == null ? null : ShareUtil.fromMatcher(m);
            if (mk != null) {
                mk.death = code.endsWith("|d");
                list.add(mk);
            }
        }
        if (!markersDirty) {
            MarkerStore.RULERS.clear();
            MarkerStore.RULERS.addAll(rulers);
        } else {
            MarkerStore.RULERS.addAll(0, rulers);
        }
        if (list.isEmpty()) { // markers from older versions (kept per world on this computer) move into this Atlas once
            List<Marker> legacy = MarkerStore.loadLegacy();
            if (legacy != null && !legacy.isEmpty()) {
                addNew(MarkerStore.MARKERS, legacy);
                MarkerStore.markLegacyImported();
                markersDirty = true;
                return;
            }
        }
        if (markersDirty) { // you added markers before the list arrived: keep both
            addNew(MarkerStore.MARKERS, list);
        } else {
            MarkerStore.MARKERS.clear();
            MarkerStore.MARKERS.addAll(list);
        }
    }

    /** The server sends the Atlas map (or chunks from a map table). */
    public static void onChunks(Net.MapChunks p) {
        if (!p.atlas().equals(active)) return; // meanwhile you switched to another Atlas
        Net.Batch b = p.batch();
        int[] bio = new int[b.biomes.size()];
        for (int i = 0; i < bio.length; i++) bio[i] = BiomeNames.index(b.biomes.get(i));
        for (Net.Chunk c : b.chunks) {
            int[] biomes = new int[16];
            for (int i = 0; i < 16; i++) biomes[i] = bio.length == 0 ? 0 : bio[c.biomes()[i]];
            int[] light = new int[256];
            for (int i = 0; i < 256; i++) light[i] = c.light()[i] & 0xFF;
            MapCache.putIncoming(b.keys.get(c.key()), c.pos(), c.colors(), c.heights(), biomes, light, c.faded());
        }
        received += b.chunks.size();
        if (p.done()) {
            loading = false;
            if (received == 0) importLegacy();
        }
    }

    /**
     * Older versions kept the map on this computer. The first time an empty Atlas is used in such a world, that
     * map moves into it (once).
     */
    private static void importLegacy() {
        Path[] files = MapCache.legacyFiles(MarkerStore.currentKey);
        boolean any = false;
        for (Path f : files) any |= Files.exists(f);
        if (!any) return;
        MapCache.load(MarkerStore.currentKey);
        int n = 0;
        for (String key : MapCache.keys()) {
            var m = MapCache.chunks(key);
            if (m == null || key.contains("#m")) continue;
            for (long pos : m.keySet().toLongArray()) {
                changed(key, pos);
                n++;
            }
        }
        for (Path f : files) {
            try {
                if (Files.exists(f)) Files.move(f, f.resolveSibling(f.getFileName() + ".imported"));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        Minecraft mc = Minecraft.getInstance();
        if (n > 0 && mc.player != null) {
            mc.player.displayClientMessage(Component.translatable("msg.wayfarers_atlas.legacy_import"), true);
        }
    }

    /** Result of using a map table. */
    public static void onTableResult(Net.TableResult r) {
        int added = 0;
        if (!r.markers().isEmpty()) {
            for (String code : r.markers()) {
                Matcher m = ShareUtil.find(code);
                if (m == null) continue;
                Marker mk = ShareUtil.fromMatcher(m);
                if (mk == null) continue;
                boolean dup = false;
                for (Marker e : MarkerStore.MARKERS) {
                    if (sameMarker(e, mk)) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    MarkerStore.MARKERS.add(mk);
                    added++;
                }
            }
            if (added > 0) MarkerStore.save();
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable("msg.wayfarers_atlas.table_done",
                    r.written(), r.received(), added), true);
        }
    }

    /** Markers to leave on a table, as share codes (kept under the size limit of one message). */
    public static List<String> markerCodes() {
        List<String> out = new ArrayList<>();
        int bytes = 0;
        for (Marker m : MarkerStore.MARKERS) {
            String c = ShareUtil.encode(m);
            bytes += c.length() * 3 + 3;
            if (bytes > 28000 || out.size() >= 1000) break;
            out.add(c);
        }
        return out;
    }

    public static void reset() {
        active = null;
        markersDirty = false;
        loading = false;
        received = 0;
        QUEUE.clear();
    }

    private AtlasSync() {}
}

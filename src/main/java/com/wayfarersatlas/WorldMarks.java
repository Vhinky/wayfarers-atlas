package com.wayfarersatlas;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Markers and regions of the server (made with /atlas): every Atlas shows them, also the Atlas of someone who
 * joins for the first time. A locked one cannot be deleted by players, only hidden in their own Atlas; a removable
 * one becomes a copy of each Atlas' own (edited, deleted like any other). A secret one is
 * shown to a player only after they have been there. Kept in the world: wayfarers_atlas/server_marks.txt.
 */
public final class WorldMarks {
    public static final int MAX_MARKERS = 500, MAX_REGIONS = 200, MAX_POINTS = 40;
    private static final int NEAR = 24; // a secret marker is found this close (blocks)

    public record SMarker(String id, String name, String dim, int x, int y, int z, int icon, int color, boolean secret, boolean free) {
        public SMarker named(String n) {
            return new SMarker(id, n, dim, x, y, z, icon, color, secret, free);
        }

        public SMarker secret(boolean s) {
            return new SMarker(id, name, dim, x, y, z, icon, color, s, free);
        }

        /** The same text an Atlas uses for its own markers. */
        public String code() {
            return "atlas|" + name + "|" + dim + "|" + x + "|" + y + "|" + z + "|" + icon + "|" + String.format("%06X", color & 0xFFFFFF);
        }
    }

    public record SRegion(String id, String name, String dim, int color, List<int[]> points, boolean secret, boolean free) {
        public SRegion named(String n) {
            return new SRegion(id, n, dim, color, points, secret, free);
        }

        public SRegion secret(boolean s) {
            return new SRegion(id, name, dim, color, points, s, free);
        }

        public String code() {
            StringBuilder sb = new StringBuilder("region|").append(dim).append('|')
                    .append(String.format("%06X", color & 0xFFFFFF)).append('|').append(name).append('|');
            for (int i = 0; i < points.size(); i++) {
                if (i > 0) sb.append(';');
                sb.append(points.get(i)[0]).append(',').append(points.get(i)[1]);
            }
            return sb.toString();
        }

        /** Is the block (x, z) inside the outline? */
        public boolean contains(double x, double z) {
            boolean in = false;
            for (int i = 0, j = points.size() - 1; i < points.size(); j = i++) {
                double xi = points.get(i)[0] + 0.5, zi = points.get(i)[1] + 0.5;
                double xj = points.get(j)[0] + 0.5, zj = points.get(j)[1] + 0.5;
                if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) in = !in;
            }
            return in;
        }
    }

    public static final Map<String, SMarker> MARKERS = new LinkedHashMap<>();
    public static final Map<String, SRegion> REGIONS = new LinkedHashMap<>();
    /** What each player has found of the secret ones: "m:id" / "r:id". */
    private static final Map<UUID, Set<String>> SEEN = new HashMap<>();
    private static Path file;

    /** A name that fits the storage format: no separators, at most 32 letters. */
    public static String cleanName(String s) {
        s = s.replace('|', ' ').replace(';', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > 32 ? s.substring(0, 32) : s;
    }

    public static boolean validId(String id) {
        return id.matches("[a-z0-9_.-]{1,32}");
    }

    // ------------------------------------------------------------------ file

    public static void start(MinecraftServer server) {
        MARKERS.clear();
        REGIONS.clear();
        SEEN.clear();
        file = server.getWorldPath(LevelResource.ROOT).resolve("wayfarers_atlas").resolve("server_marks.txt").normalize();
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                try {
                    read(line);
                } catch (RuntimeException e) {
                    LogUtils.getLogger().warn("Wayfarer's Atlas: skipped a bad line in {}: {}", file, line);
                }
            }
        } catch (IOException e) {
            LogUtils.getLogger().warn("Wayfarer's Atlas: could not read {}", file, e);
        }
    }

    public static void stop() {
        MARKERS.clear();
        REGIONS.clear();
        SEEN.clear();
        file = null;
    }

    /** Lines: marker|id|flags|<marker code>,  region|id|flags|<region code>,  seen|player|key  (flags: s secret, f removable) */
    private static void read(String line) {
        if (line.isBlank()) return;
        String[] p = line.split("\\|", 4);
        switch (p[0]) {
            case "marker" -> {
                String[] c = p[3].split("\\|", -1); // atlas|name|dim|x|y|z|icon|RRGGBB
                MARKERS.put(p[1], new SMarker(p[1], c[1], c[2], Integer.parseInt(c[3]), Integer.parseInt(c[4]), Integer.parseInt(c[5]),
                        Integer.parseInt(c[6]), Integer.parseInt(c[7], 16), p[2].contains("s"), p[2].contains("f")));
            }
            case "region" -> {
                String[] c = p[3].split("\\|", -1); // region|dim|RRGGBB|name|x,z;x,z
                List<int[]> pts = new ArrayList<>();
                for (String s : c[4].split(";")) {
                    String[] xz = s.split(",");
                    pts.add(new int[]{Integer.parseInt(xz[0].trim()), Integer.parseInt(xz[1].trim())});
                }
                if (pts.size() >= 3) REGIONS.put(p[1], new SRegion(p[1], c[3], c[1], Integer.parseInt(c[2], 16), pts, p[2].contains("s"), p[2].contains("f")));
            }
            case "seen" -> SEEN.computeIfAbsent(UUID.fromString(p[1]), k -> new HashSet<>()).add(p[2]);
            default -> {
            }
        }
    }

    private static void write() {
        if (file == null) return;
        List<String> lines = new ArrayList<>();
        for (SMarker m : MARKERS.values()) lines.add("marker|" + m.id + "|" + flags(m.secret, m.free) + "|" + m.code());
        for (SRegion r : REGIONS.values()) lines.add("region|" + r.id + "|" + flags(r.secret, r.free) + "|" + r.code());
        for (var e : SEEN.entrySet()) for (String k : e.getValue()) lines.add("seen|" + e.getKey() + "|" + k);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LogUtils.getLogger().warn("Wayfarer's Atlas: could not save {}", file, e);
        }
    }

    private static String flags(boolean secret, boolean free) {
        String f = (secret ? "s" : "") + (free ? "f" : "");
        return f.isEmpty() ? "-" : f;
    }

    // ------------------------------------------------------------------ changes (from the commands)

    /** Something was added, changed or removed: save and tell everyone. */
    public static void changed(MinecraftServer server) {
        // what was found of things that no longer exist is forgotten
        for (Set<String> seen : SEEN.values()) {
            seen.removeIf(k -> k.startsWith("m:") ? !MARKERS.containsKey(k.substring(2)) : !REGIONS.containsKey(k.substring(2)));
        }
        write();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) send(p);
    }

    // ------------------------------------------------------------------ to the players

    private static boolean seen(ServerPlayer p, String key) {
        Set<String> s = SEEN.get(p.getUUID());
        return s != null && s.contains(key);
    }

    /** Everything this player may see: "m|id|<marker code>" and "r|id|<region code>" (M / R: removable ones). */
    public static void send(ServerPlayer p) {
        List<String> out = new ArrayList<>();
        for (SMarker m : MARKERS.values()) if (!m.secret || seen(p, "m:" + m.id)) out.add((m.free ? "M|" : "m|") + m.id + "|" + m.code());
        for (SRegion r : REGIONS.values()) if (!r.secret || seen(p, "r:" + r.id)) out.add((r.free ? "R|" : "r|") + r.id + "|" + r.code());
        PacketDistributor.sendToPlayer(p, new Net.ServerMarksMsg(out));
    }

    /** Once a second: a player who comes to a secret marker or walks into a secret region gets it on the map. */
    public static void tick(MinecraftServer server) {
        if (file == null) return;
        boolean any = false;
        for (SMarker m : MARKERS.values()) any |= m.secret;
        for (SRegion r : REGIONS.values()) any |= r.secret;
        if (!any) return;
        boolean save = false;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isSpectator()) continue;
            String dim = p.level().dimension().location().toString();
            boolean found = false;
            for (SMarker m : MARKERS.values()) {
                if (!m.secret || !m.dim.equals(dim) || seen(p, "m:" + m.id)) continue;
                double dx = m.x + 0.5 - p.getX(), dz = m.z + 0.5 - p.getZ();
                double dy = m.y == Marker.NO_Y ? 0 : m.y - p.getY();
                if (dx * dx + dz * dz <= NEAR * NEAR && Math.abs(dy) <= NEAR) {
                    SEEN.computeIfAbsent(p.getUUID(), k -> new HashSet<>()).add("m:" + m.id);
                    found = true;
                }
            }
            for (SRegion r : REGIONS.values()) {
                if (!r.secret || !r.dim.equals(dim) || seen(p, "r:" + r.id)) continue;
                if (r.contains(p.getX(), p.getZ())) {
                    SEEN.computeIfAbsent(p.getUUID(), k -> new HashSet<>()).add("r:" + r.id);
                    found = true;
                }
            }
            if (found) {
                save = true;
                send(p);
            }
        }
        if (save) write();
    }

    private WorldMarks() {}
}

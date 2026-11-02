package com.wayfarersatlas;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Markers are stored per world (singleplayer) or per server address (multiplayer). */
public final class MarkerStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final List<Marker> MARKERS = new ArrayList<>();

    /** A ruler left on the map: points (block x, z) in one dimension. Kept in the Atlas like markers. */
    public static final class Ruler {
        public final String dim;
        public final List<int[]> points;

        public Ruler(String dim, List<int[]> points) {
            this.dim = dim;
            this.points = points;
        }

        public String encode() {
            StringBuilder sb = new StringBuilder("ruler|").append(dim).append('|');
            for (int i = 0; i < points.size(); i++) {
                if (i > 0) sb.append(';');
                sb.append(points.get(i)[0]).append(',').append(points.get(i)[1]);
            }
            return sb.toString();
        }

        public static Ruler decode(String code) {
            try {
                String[] parts = code.split("\\|");
                if (parts.length != 3 || !parts[0].equals("ruler")) return null;
                List<int[]> pts = new ArrayList<>();
                for (String p : parts[2].split(";")) {
                    String[] xz = p.split(",");
                    pts.add(new int[]{Integer.parseInt(xz[0].trim()), Integer.parseInt(xz[1].trim())});
                }
                return pts.size() >= 2 ? new Ruler(parts[1], pts) : null;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    public static final List<Ruler> RULERS = new ArrayList<>();

    /**
     * A named area drawn on the map (a village, your base...): an outline of points (block x, z), a colour and a
     * name. Kept in the Atlas like markers and can be left on a map table.
     */
    public static final class Region {
        public static final int MAX_POINTS = 40;
        public String name;
        public int color; // 0xRRGGBB
        public final String dim;
        public final List<int[]> points;

        public Region(String name, int color, String dim, List<int[]> points) {
            this.name = name;
            this.color = color;
            this.dim = dim;
            this.points = points;
        }

        public String encode() {
            StringBuilder sb = new StringBuilder("region|").append(dim).append('|')
                    .append(String.format("%06X", color & 0xFFFFFF)).append('|')
                    .append(name.replace('|', ' ').replace(';', ' ')).append('|');
            for (int i = 0; i < points.size(); i++) {
                if (i > 0) sb.append(';');
                sb.append(points.get(i)[0]).append(',').append(points.get(i)[1]);
            }
            return sb.toString();
        }

        public static Region decode(String code) {
            try {
                String[] parts = code.split("\\|", -1);
                if (parts.length != 5 || !parts[0].equals("region")) return null;
                List<int[]> pts = new ArrayList<>();
                for (String p : parts[4].split(";")) {
                    String[] xz = p.split(",");
                    pts.add(new int[]{Integer.parseInt(xz[0].trim()), Integer.parseInt(xz[1].trim())});
                    if (pts.size() >= MAX_POINTS) break;
                }
                if (pts.size() < 3) return null;
                String name = parts[3].length() > 32 ? parts[3].substring(0, 32) : parts[3];
                return new Region(name, Integer.parseInt(parts[2], 16) & 0xFFFFFF, parts[1], pts);
            } catch (RuntimeException e) {
                return null;
            }
        }

        private int[] tris; // triangles of the outline (indices of points), worked out once

        /** The outline cut into triangles (ear clipping), as point indices in threes. */
        public int[] triangles() {
            if (tris != null) return tris;
            int n = points.size();
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < n; i++) idx.add(i);
            double area = 0;
            for (int i = 0, j = n - 1; i < n; j = i++) area += (double) points.get(j)[0] * points.get(i)[1] - (double) points.get(i)[0] * points.get(j)[1];
            boolean ccw = area > 0;
            List<Integer> out = new ArrayList<>();
            int guard = 0;
            while (idx.size() > 3 && guard++ < 10000) {
                boolean cut = false;
                for (int k = 0; k < idx.size(); k++) {
                    int a = idx.get((k + idx.size() - 1) % idx.size()), b = idx.get(k), c = idx.get((k + 1) % idx.size());
                    if (!convex(a, b, c, ccw)) continue;
                    boolean inside = false;
                    for (int o : idx) {
                        if (o == a || o == b || o == c) continue;
                        if (inTri(o, a, b, c)) {
                            inside = true;
                            break;
                        }
                    }
                    if (inside) continue;
                    out.add(a);
                    out.add(b);
                    out.add(c);
                    idx.remove(k);
                    cut = true;
                    break;
                }
                if (!cut) break; // crossed outline: what is left is not filled
            }
            if (idx.size() == 3) {
                out.add(idx.get(0));
                out.add(idx.get(1));
                out.add(idx.get(2));
            }
            tris = new int[out.size()];
            for (int i = 0; i < tris.length; i++) tris[i] = out.get(i);
            return tris;
        }

        private double cross(int a, int b, int c) {
            int[] pa = points.get(a), pb = points.get(b), pc = points.get(c);
            return (double) (pb[0] - pa[0]) * (pc[1] - pa[1]) - (double) (pb[1] - pa[1]) * (pc[0] - pa[0]);
        }

        private boolean convex(int a, int b, int c, boolean ccw) {
            double cr = cross(a, b, c);
            return ccw ? cr > 0 : cr < 0;
        }

        private boolean inTri(int p, int a, int b, int c) {
            double d1 = cross(a, b, p), d2 = cross(b, c, p), d3 = cross(c, a, p);
            boolean neg = d1 < 0 || d2 < 0 || d3 < 0, pos = d1 > 0 || d2 > 0 || d3 > 0;
            return !(neg && pos);
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

    public static final List<Region> REGIONS = new ArrayList<>();
    public static String currentKey = "unknown";

    public static Path dir() {
        Path dir = FMLPaths.GAMEDIR.get().resolve("wayfarers_atlas");
        if (!migrated) {
            migrated = true;
            // maps and markers from the time the mod was called "Atlas Lite": move them over once
            Path old = FMLPaths.GAMEDIR.get().resolve("atlaslite");
            if (!Files.exists(dir) && Files.isDirectory(old)) {
                try {
                    Files.move(old, dir);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return dir;
    }

    private static boolean migrated = false;

    public static String computeWorldKey() {
        Minecraft mc = Minecraft.getInstance();
        String raw;
        if (mc.getSingleplayerServer() != null) {
            raw = "sp_" + mc.getSingleplayerServer().getWorldData().getLevelName();
        } else if (mc.getCurrentServer() != null) {
            raw = "mp_" + mc.getCurrentServer().ip;
        } else {
            raw = "unknown";
        }
        return raw.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /** Markers file kept on this computer by older versions (before markers moved into the Atlas). */
    public static Path legacyFile() {
        return dir().resolve(currentKey + ".markers.json");
    }

    /** Reads the old per-world markers file, or null if there is none. */
    public static List<Marker> loadLegacy() {
        Path file = legacyFile();
        if (!Files.exists(file)) return null;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<Marker> list = GSON.fromJson(r, new TypeToken<List<Marker>>() {}.getType());
            if (list == null) return null;
            for (Marker m : list) {
                if (m.iconSet < 2) { // saved with the old icon set
                    m.icon = Icons.fromOldSet(m.icon);
                    m.iconSet = 2;
                }
            }
            return list;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static void markLegacyImported() {
        try {
            Path f = legacyFile();
            if (Files.exists(f)) Files.move(f, f.resolveSibling(f.getFileName() + ".imported"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Markers belong to the Atlas you carry: after any change they are sent to the server and kept inside it,
     * so another Atlas has its own markers.
     */
    public static void save() {
        AtlasSync.markersChanged();
    }

    private MarkerStore() {}
}

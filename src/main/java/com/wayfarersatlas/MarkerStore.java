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
        public String folder; // folder in the list (kept with a "|g:<name>" ending), or null

        /** Not drawn: its folder is hidden. */
        public boolean off() {
            return folder != null && folderHidden(folder);
        }

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
        public String serverId; // a region of the server (/atlas region): not edited or deleted, only hidden
        public String teamUid;  // a region of your FTB team (kept by the server for the team)
        public boolean hidden;  // a server region hidden in this Atlas (found only in the list)
        public String folder;   // folder in the list (kept with a "|g:<name>" ending), or null

        /** Not drawn: hidden itself, or its folder is hidden. */
        public boolean off() {
            return hidden || folder != null && folderHidden(folder);
        }
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

        private double area = -1;

        /** Area of the outline in blocks (worked out once). */
        public double area() {
            if (area >= 0) return area;
            double a = 0;
            for (int i = 0, j = points.size() - 1; i < points.size(); j = i++) {
                a += (double) points.get(j)[0] * points.get(i)[1] - (double) points.get(i)[0] * points.get(j)[1];
            }
            return area = Math.abs(a) / 2;
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

    /** A folder in the list of markers. Hidden: none of its markers are drawn on the maps or in the world. */
    public static final class Folder {
        public static final int MAX_NAME = 24;
        public String name;
        public boolean hidden;
        public transient boolean closed; // folded in the list (not kept)

        public Folder(String name, boolean hidden) {
            this.name = name;
            this.hidden = hidden;
        }

        public String encode() {
            return "folder|" + clean(name) + "|" + (hidden ? "h" : "");
        }

        public static Folder decode(String code) {
            String[] p = code.split("\\|", -1);
            if (p.length < 2 || !p[0].equals("folder") || p[1].isBlank()) return null;
            return new Folder(clean(p[1]), p.length > 2 && p[2].contains("h"));
        }

        /** A name that fits into the codes (no "|", not too long). */
        public static String clean(String name) {
            String n = name.replace('|', ' ').replace(';', ' ').trim();
            return n.length() > MAX_NAME ? n.substring(0, MAX_NAME) : n;
        }
    }

    public static final List<Folder> FOLDERS = new ArrayList<>();

    public static Folder folder(String name) {
        if (name == null) return null;
        for (Folder f : FOLDERS) if (f.name.equals(name)) return f;
        return null;
    }

    public static boolean folderHidden(String name) {
        Folder f = folder(name);
        return f != null && f.hidden;
    }

    /** Your own markers, regions and rulers can go into folders (not the server's or the team's). */
    public static boolean ownItem(Object o) {
        if (o instanceof Marker m) return m.serverId == null && m.teamUid == null;
        if (o instanceof Region r) return r.serverId == null && r.teamUid == null;
        return o instanceof Ruler;
    }

    /** The folder of a marker, region or ruler (null: none, or the folder is gone). */
    public static String folderOf(Object o) {
        String f = o instanceof Marker m ? m.folder : o instanceof Region r ? r.folder : o instanceof Ruler u ? u.folder : null;
        return folder(f) == null ? null : f;
    }

    public static void setFolder(Object o, String folder) {
        if (o instanceof Marker m) m.folder = folder;
        else if (o instanceof Region r) r.folder = folder;
        else if (o instanceof Ruler u) u.folder = folder;
    }

    /** Everything kept in folders: renaming / deleting a folder goes through it. */
    public static List<Object> folderItems() {
        List<Object> out = new ArrayList<>(MARKERS);
        out.addAll(REGIONS);
        out.addAll(RULERS);
        return out;
    }

    /** " |g:<folder>" ending of a code in the Atlas, or "". */
    public static String folderSuffix(String folder) {
        return folder != null && folder(folder) != null ? "|g:" + Folder.clean(folder) : "";
    }

    /** The folder named in a code's ending, or null. */
    public static String folderIn(String code) {
        int g = code.indexOf("|g:");
        if (g < 0) return null;
        int end = code.indexOf('|', g + 3);
        String f = code.substring(g + 3, end < 0 ? code.length() : end);
        return f.isBlank() ? null : f;
    }

    /** The code without its folder ending. */
    public static String withoutFolder(String code) {
        int g = code.indexOf("|g:");
        if (g < 0) return code;
        int end = code.indexOf('|', g + 3);
        return code.substring(0, g) + (end < 0 ? "" : code.substring(end));
    }

    /**
     * A line drawn on the map with the pencil: points in quarter blocks (x, z, x, z...), a colour and a width
     * (0..2). Kept in the Atlas like markers; a long drawing is kept in several parts.
     */
    public static final class Stroke {
        public static final int MAX_POINTS = 90; // per part (a code stays under 2000 characters)
        public static final int[] COLORS = {0xB22222, 0x1E1E1E, 0xF0F0F0, 0xDCAA1E, 0x2860C8, 0x288C3C};
        public final String dim;
        public final int color;
        public final int width;
        public final int[] pts;
        public transient long group; // the parts of one drawing share it (undo takes them all back)
        private transient double[] box;

        public Stroke(String dim, int color, int width, int[] pts) {
            this.dim = dim;
            this.color = color & 0xFFFFFF;
            this.width = Math.max(0, Math.min(2, width));
            this.pts = pts;
        }

        public int count() {
            return pts.length / 2;
        }

        public double x(int i) {
            return pts[i * 2] / 4.0;
        }

        public double z(int i) {
            return pts[i * 2 + 1] / 4.0;
        }

        /** min x, min z, max x, max z in blocks. */
        public double[] box() {
            if (box == null) {
                double x0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
                for (int i = 0; i < count(); i++) {
                    x0 = Math.min(x0, x(i));
                    z0 = Math.min(z0, z(i));
                    x1 = Math.max(x1, x(i));
                    z1 = Math.max(z1, z(i));
                }
                box = new double[]{x0, z0, x1, z1};
            }
            return box;
        }

        public String encode() {
            StringBuilder sb = new StringBuilder("stroke|").append(dim).append('|')
                    .append(String.format("%06X", color)).append('|').append(width).append('|');
            for (int i = 0; i < count(); i++) {
                if (i > 0) sb.append(';');
                sb.append(pts[i * 2]).append(',').append(pts[i * 2 + 1]);
            }
            return sb.toString();
        }

        public static Stroke decode(String code) {
            try {
                String[] p = code.split("\\|", -1);
                if (p.length != 5 || !p[0].equals("stroke")) return null;
                String[] s = p[4].split(";");
                if (s.length < 2 || s.length > MAX_POINTS) return null;
                int[] pts = new int[s.length * 2];
                for (int i = 0; i < s.length; i++) {
                    String[] xz = s[i].split(",");
                    pts[i * 2] = Integer.parseInt(xz[0].trim());
                    pts[i * 2 + 1] = Integer.parseInt(xz[1].trim());
                }
                return new Stroke(p[1], Integer.parseInt(p[2], 16), Integer.parseInt(p[3]), pts);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    public static final List<Stroke> STROKES = new ArrayList<>();

    /** The markers of the Atlas together with the server's (those come first, yours are drawn over them). */
    public static List<Marker> allMarkers() {
        Marker led = Nav.target(); // the place "/atlas lead" leads to is shown as a marker while you follow it
        boolean lead = led != null && ServerMarks.LEAD.equals(led.serverId);
        if (ServerMarks.MARKERS.isEmpty() && TeamMarks.MARKERS.isEmpty() && !lead) return MARKERS;
        List<Marker> out = new ArrayList<>(ServerMarks.MARKERS.size() + TeamMarks.MARKERS.size() + MARKERS.size() + 1);
        out.addAll(ServerMarks.MARKERS);
        out.addAll(TeamMarks.MARKERS);
        out.addAll(MARKERS);
        if (lead) out.add(led);
        return out;
    }

    /** The regions of the Atlas together with the server's. */
    public static List<Region> allRegions() {
        if (ServerMarks.REGIONS.isEmpty() && TeamMarks.REGIONS.isEmpty()) return REGIONS;
        List<Region> out = new ArrayList<>(ServerMarks.REGIONS.size() + TeamMarks.REGIONS.size() + REGIONS.size());
        out.addAll(ServerMarks.REGIONS);
        out.addAll(TeamMarks.REGIONS);
        out.addAll(REGIONS);
        return out;
    }
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

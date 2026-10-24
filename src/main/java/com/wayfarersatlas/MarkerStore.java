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

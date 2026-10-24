package com.wayfarersatlas;

import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Brings waypoints over from Xaero's Minimap (its files in the game folder), so moving to the
 * Atlas does not cost you your markers. Imported markers go into the Atlas that is shown, as plain pins.
 */
public final class WaypointImport {
    /** One world of one mod that has waypoints: the files that hold them. */
    public record Source(String mod, String world, List<Path> files) {
        public String label() {
            return mod + ": " + world;
        }
    }

    // Minecraft chat colours used by Xaero's (0..15)
    private static final int[] XAERO_COLORS = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

    /**
     * Looks everywhere Xaero's has kept waypoints over the years (game folder, config, inside world saves)
     * and groups the files by world.
     */
    public static List<Source> find() {
        Path game = FMLPaths.GAMEDIR.get();
        Map<String, List<Path>> xaero = new TreeMap<>();
        List<Path> xRoots = new ArrayList<>(List.of(game.resolve("XaeroWaypoints"), game.resolve("xaero"),
                game.resolve("config").resolve("xaero")));
        for (Path save : list(game.resolve("saves"))) xRoots.add(save.resolve("xaero"));
        for (Path root : xRoots) {
            for (Path f : walk(root, 8)) {
                if (!f.toString().endsWith(".txt") || !hasXaeroLines(f)) continue;
                xaero.computeIfAbsent(worldName(game, root, f), k -> new ArrayList<>()).add(f);
            }
        }
        List<Source> out = new ArrayList<>();
        xaero.forEach((w, files) -> out.add(new Source("Xaero's", w, files)));
        return out;
    }

    /** A readable world name for a file: the folder under minimap / XaeroWaypoints, or the save. */
    private static String worldName(Path game, Path root, Path f) {
        Path rel = root.relativize(f);
        List<String> parts = new ArrayList<>();
        for (Path p : rel) parts.add(p.toString());
        if (root.getParent() != null && root.getParent().getParent() != null
                && root.getParent().getParent().getFileName().toString().equals("saves")) {
            return root.getParent().getFileName().toString();
        }
        for (int i = 0; i < parts.size() - 1; i++) {
            String s = parts.get(i);
            if ((s.equals("minimap") || s.equals("sp") || s.equals("mp") || s.equals("world-map")) && i + 1 < parts.size() - 1) {
                return parts.get(i + 1);
            }
        }
        return parts.size() > 1 ? parts.get(0) : root.getFileName().toString();
    }

    private static List<Path> list(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static List<Path> walk(Path dir, int depth) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.walk(dir, depth)) {
            return s.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private static boolean hasXaeroLines(Path f) {
        try (Stream<String> lines = Files.lines(f, StandardCharsets.UTF_8)) {
            return lines.anyMatch(l -> l.startsWith("waypoint:"));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Adds the waypoints of a source to the markers; returns how many were new. */
    public static int importFrom(Source src) {
        List<Marker> found = new ArrayList<>();
        for (Path f : src.files()) readXaero(f, found);
        int added = 0;
        for (Marker m : found) {
            boolean dup = false;
            for (Marker e : MarkerStore.MARKERS) {
                if (e.dim.equals(m.dim) && e.x == m.x && e.z == m.z && e.name.equals(m.name)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                MarkerStore.MARKERS.add(m);
                added++;
            }
        }
        if (added > 0) MarkerStore.save();
        return added;
    }

    // ------------------------------------------------------------------ Xaero's Minimap

    /** The dimension of a Xaero's file: from the nearest dim%... folder above it (overworld if none). */
    private static String xaeroDim(Path f) {
        for (Path p = f.getParent(); p != null; p = p.getParent()) {
            String n = p.getFileName() == null ? "" : p.getFileName().toString();
            if (!n.startsWith("dim%")) continue;
            String d = n.substring(4);
            return switch (d) {
                case "0" -> "minecraft:overworld";
                case "-1" -> "minecraft:the_nether";
                case "1" -> "minecraft:the_end";
                default -> d.contains("$") ? d.replace('$', ':') : d.contains(":") ? d : "minecraft:" + d;
            };
        }
        return "minecraft:overworld";
    }

    private static void readXaero(Path f, List<Marker> out) {
        String dim = xaeroDim(f);
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (!line.startsWith("waypoint:")) continue;
                String[] p = line.split(":");
                if (p.length < 7) continue;
                try {
                    String name = p[1].replace("\u00a7\u00a7", ":");
                    int x = Integer.parseInt(p[3].trim());
                    int y = p[4].trim().equals("~") ? Marker.NO_Y : Integer.parseInt(p[4].trim());
                    int z = Integer.parseInt(p[5].trim());
                    int ci = Integer.parseInt(p[6].trim());
                    int rgb = ci >= 0 && ci < 16 ? XAERO_COLORS[ci] : 0xFFFFFF;
                    out.add(marker(name, dim, x, y, z, rgb));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    /** A plain pin in the palette colour closest to the original. */
    private static Marker marker(String name, String dim, int x, int y, int z, int rgb) {
        int best = MarkerScreen.PALETTE[0];
        long bestD = Long.MAX_VALUE;
        for (int c : MarkerScreen.PALETTE) {
            long dr = ((c >> 16) & 0xFF) - ((rgb >> 16) & 0xFF), dg = ((c >> 8) & 0xFF) - ((rgb >> 8) & 0xFF), db = (c & 0xFF) - (rgb & 0xFF);
            long d = dr * dr + dg * dg + db * db;
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        String n = name.length() > 32 ? name.substring(0, 32) : name;
        return new Marker(n.replace('|', ' '), dim, x, y, z, best, 0);
    }

    private WaypointImport() {}
}

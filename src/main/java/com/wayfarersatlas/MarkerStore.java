package com.wayfarersatlas;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Markers are stored per world (singleplayer) or per server address (multiplayer). */
public final class MarkerStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final List<Marker> MARKERS = new ArrayList<>();
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

    public static void load() {
        MARKERS.clear();
        Path file = dir().resolve(currentKey + ".markers.json");
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<Marker> list = GSON.fromJson(r, new TypeToken<List<Marker>>() {}.getType());
            if (list != null) {
                for (Marker m : list) {
                    if (m.iconSet < 2) { // saved with the old icon set
                        m.icon = Icons.fromOldSet(m.icon);
                        m.iconSet = 2;
                    }
                }
                MARKERS.addAll(list);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void save() {
        try {
            Files.createDirectories(dir());
            try (Writer w = Files.newBufferedWriter(dir().resolve(currentKey + ".markers.json"), StandardCharsets.UTF_8)) {
                GSON.toJson(MARKERS, w);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private MarkerStore() {}
}

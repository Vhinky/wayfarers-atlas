package com.wayfarersatlas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * When an update adds new settings, NeoForge fills them into the config file and keeps a .bak copy of the old
 * one every time. Those copies of our own config are useless, so they are removed once the config is loaded.
 */
public final class ConfigCleanup {
    public static void clean(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) {
                String n = p.getFileName().toString();
                if (n.startsWith(WayfarersAtlas.MODID) && n.contains(".bak")) {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private ConfigCleanup() {}
}

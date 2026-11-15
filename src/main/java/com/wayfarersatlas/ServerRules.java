package com.wayfarersatlas;

/** Client side copy of the server rules (everything allowed until the server says otherwise). */
public final class ServerRules {
    private static int flags = -1;
    private static java.util.Set<String> offDims = java.util.Set.of();

    public static void set(int f, java.util.List<String> off) {
        flags = f;
        offDims = java.util.Set.copyOf(off);
    }

    public static void reset() {
        flags = -1;
        offDims = java.util.Set.of();
    }

    public static boolean teleport() {
        return (flags & 1) != 0;
    }

    public static boolean radar() {
        return (flags & 2) != 0;
    }

    public static boolean deathPage() {
        return (flags & 4) != 0;
    }

    public static boolean mapTable() {
        return (flags & 8) != 0;
    }

    public static boolean trail() {
        return (flags & 16) != 0;
    }

    public static boolean showPlayers() {
        return (flags & 32) != 0;
    }

    public static boolean itemsPage() {
        return (flags & 64) != 0;
    }

    public static boolean manualCaves() {
        return (flags & 128) != 0;
    }

    /** Coordinates may be shown. */
    public static boolean coords() {
        return (flags & 256) != 0;
    }

    public static boolean minimap() {
        return (flags & 512) != 0;
    }

    /** The server switched the map off in this world. */
    public static boolean mapOff(String dim) {
        return offDims.contains(dim);
    }

    private ServerRules() {}
}

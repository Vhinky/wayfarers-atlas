package com.wayfarersatlas;

/** Client side copy of the server rules (everything allowed until the server says otherwise). */
public final class ServerRules {
    private static int flags = -1;

    public static void set(int f) {
        flags = f;
    }

    public static void reset() {
        flags = -1;
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

    private ServerRules() {}
}

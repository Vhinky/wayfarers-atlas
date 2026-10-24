package com.wayfarersatlas;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server rules (world/serverconfig/wayfarers_atlas-server.toml, or the defaults in defaultconfigs/).
 * Sent to every player when they join and when the file changes; the client hides what is switched off.
 */
public final class ServerConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue TELEPORT;
    public static final ModConfigSpec.BooleanValue RADAR;
    public static final ModConfigSpec.BooleanValue DEATH_PAGE;
    public static final ModConfigSpec.BooleanValue MAP_TABLE;
    public static final ModConfigSpec.BooleanValue TRAIL;
    public static final ModConfigSpec.BooleanValue SHOW_PLAYERS;
    public static final ModConfigSpec.BooleanValue ITEM_PAGE;

    private static String t(String name) {
        return "wayfarers_atlas.configuration.server." + name;
    }

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.translation(t("rules")).push("rules");
        TELEPORT = b.comment("Teleport from the map (players still need operator rights)").translation(t("teleport"))
                .define("teleport", true);
        RADAR = b.comment("Creature Radar page works").translation(t("radar")).define("radar", true);
        DEATH_PAGE = b.comment("Death page works (shows where you died)").translation(t("death_page")).define("deathPage", true);
        MAP_TABLE = b.comment("Wayfarer's Map Table can be used").translation(t("map_table")).define("mapTable", true);
        TRAIL = b.comment("Dotted trail of the last minute on the maps").translation(t("trail")).define("trail", true);
        SHOW_PLAYERS = b.comment("Other players are shown on the maps").translation(t("show_players"))
                .define("showPlayers", true);
        ITEM_PAGE = b.comment("Items page works (shows items lying on the ground)").translation(t("item_page"))
                .define("itemPage", true);
        b.pop();
        SPEC = b.build();
    }

    /** The rules as bit flags, for the network message. */
    public static int flags() {
        int f = 0;
        if (TELEPORT.get()) f |= 1;
        if (RADAR.get()) f |= 2;
        if (DEATH_PAGE.get()) f |= 4;
        if (MAP_TABLE.get()) f |= 8;
        if (TRAIL.get()) f |= 16;
        if (SHOW_PLAYERS.get()) f |= 32;
        if (ITEM_PAGE.get()) f |= 64;
        return f;
    }

    private ServerConfig() {}
}

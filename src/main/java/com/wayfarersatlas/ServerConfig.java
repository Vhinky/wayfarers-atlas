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
    public static final ModConfigSpec.BooleanValue MANUAL_CAVES;
    public static final ModConfigSpec.BooleanValue COORDINATES;
    public static final ModConfigSpec.BooleanValue MINIMAP;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> MAP_OFF_DIMENSIONS;
    public static final ModConfigSpec.BooleanValue STARTER_ATLAS;

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
        MANUAL_CAVES = b.comment("Manual cave mode can be used (it only shows caves the player has been in)")
                .translation(t("manual_caves")).define("manualCaves", true);
        COORDINATES = b.comment("Coordinates are shown (under the minimap, on the map, in marker lists)")
                .translation(t("coordinates")).define("coordinates", true);
        MINIMAP = b.comment("The minimap can be used (off: only the big map)").translation(t("minimap")).define("minimap", true);
        MAP_OFF_DIMENSIONS = b.comment("Worlds where the map does not work at all, for example [\"minecraft:the_end\"]")
                .translation(t("map_off_dimensions"))
                .defineListAllowEmpty("mapOffDimensions", java.util.List.of(), () -> "", o -> o instanceof String);
        b.pop();
        b.translation(t("start")).push("start");
        STARTER_ATLAS = b.comment("Everyone gets an Atlas when they join the world for the first time")
                .translation(t("starter_atlas")).define("starterAtlas", false);
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
        if (MANUAL_CAVES.get()) f |= 128;
        if (COORDINATES.get()) f |= 256;
        if (MINIMAP.get()) f |= 512;
        return f;
    }

    /** Worlds where the map does not work, for the network message. */
    public static java.util.List<String> offDims() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String s : MAP_OFF_DIMENSIONS.get()) {
            if (s != null && !s.isBlank() && s.length() <= 200 && out.size() < 256) out.add(s.trim());
        }
        return out;
    }

    private ServerConfig() {}
}

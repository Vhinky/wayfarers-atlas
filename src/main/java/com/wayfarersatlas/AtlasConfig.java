package com.wayfarersatlas;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client settings (config/wayfarers_atlas-client.toml). Editable in game: Mods -> Wayfarer's Atlas -> Config,
 * or with the gear button on the map. Every change applies right away.
 */
public final class AtlasConfig {
    public enum Corner { TOP_RIGHT, TOP_LEFT, BOTTOM_RIGHT, BOTTOM_LEFT }

    public static final ModConfigSpec SPEC;

    // minimap
    public static final ModConfigSpec.BooleanValue MINIMAP;
    public static final ModConfigSpec.EnumValue<Corner> MINIMAP_CORNER;
    public static final ModConfigSpec.IntValue MINIMAP_SIZE;
    public static final ModConfigSpec.BooleanValue MINIMAP_ROUND;
    public static final ModConfigSpec.BooleanValue SHOW_COORDS;
    public static final ModConfigSpec.BooleanValue SHOW_BIOME;
    // map
    public static final ModConfigSpec.BooleanValue SHOW_PLAYERS;
    public static final ModConfigSpec.BooleanValue NIGHT_VIEW;
    public static final ModConfigSpec.IntValue RELIEF;
    public static final ModConfigSpec.IntValue MARKER_SIZE;
    public static final ModConfigSpec.EnumValue<MapScanner.CaveMode> CAVE_MODE;
    public static final ModConfigSpec.IntValue SCAN_DISTANCE;
    public static final ModConfigSpec.BooleanValue OPEN_SOUND;
    public static final ModConfigSpec.BooleanValue TRAIL;
    public static final ModConfigSpec.BooleanValue BURNT_EDGES;
    // radar upgrade
    public static final ModConfigSpec.BooleanValue RADAR;
    public static final ModConfigSpec.BooleanValue RADAR_PASSIVE;
    public static final ModConfigSpec.BooleanValue RADAR_ITEMS;
    public static final ModConfigSpec.BooleanValue TELEPORT;
    // general
    public static final ModConfigSpec.BooleanValue REQUIRE_ATLAS;
    public static final ModConfigSpec.IntValue SLOT_X;
    public static final ModConfigSpec.IntValue SLOT_Y;
    public static final ModConfigSpec.BooleanValue NORTH_UP;

    private static String t(String name) {
        return "wayfarers_atlas.configuration." + name;
    }

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.translation(t("minimap")).push("minimap");
        MINIMAP = b.comment("Show the minimap").translation(t("minimap_enabled")).define("enabled", true);
        MINIMAP_CORNER = b.comment("Screen corner of the minimap").translation(t("minimap_corner"))
                .defineEnum("corner", Corner.TOP_RIGHT);
        MINIMAP_SIZE = b.comment("Minimap size in GUI pixels").translation(t("minimap_size"))
                .defineInRange("size", 105, 64, 200);
        MINIMAP_ROUND = b.comment("Round minimap (in a brass ring) instead of a square one").translation(t("minimap_round"))
                .define("round", false);
        SHOW_COORDS = b.comment("Coordinates under the minimap").translation(t("show_coords")).define("coordinates", true);
        NORTH_UP = b.comment("North always up on the minimap (off: the minimap turns with you)").translation(t("north_up"))
                .define("northUp", true);
        SHOW_BIOME = b.comment("Biome name under the minimap").translation(t("show_biome")).define("biome", true);
        b.pop();

        b.translation(t("map")).push("map");
        SHOW_PLAYERS = b.comment("Show other players (their heads) on the maps").translation(t("show_players"))
                .define("players", true);
        NIGHT_VIEW = b.comment("Darken the map at night; lit places glow").translation(t("night_view")).define("nightView", true);
        RELIEF = b.comment("Strength of the relief shading in percent (applies to newly mapped areas)")
                .translation(t("relief")).defineInRange("relief", 100, 0, 200);
        MARKER_SIZE = b.comment("Size of marker icons in percent").translation(t("marker_size"))
                .defineInRange("markerSize", 100, 50, 200);
        CAVE_MODE = b.comment("Cave view when joining a world").translation(t("cave_mode"))
                .defineEnum("caveMode", MapScanner.CaveMode.AUTO);
        SCAN_DISTANCE = b.comment("How far (in chunks) the map is explored. 0 = your render distance")
                .translation(t("scan_distance")).defineInRange("scanDistance", 0, 0, 64);
        TRAIL = b.comment("Dotted trail of where you walked during the last minute").translation(t("trail"))
                .define("trail", true);
        BURNT_EDGES = b.comment("The map ends in singed, ragged edges next to unexplored land").translation(t("burnt_edges"))
                .define("burntEdges", true);
        OPEN_SOUND = b.comment("Page sound when opening the map").translation(t("open_sound")).define("openSound", true);
        TELEPORT = b.comment("Teleport entries in the map menu (needs permission)").translation(t("teleport"))
                .define("teleport", true);
        b.pop();

        b.translation(t("radar")).push("radar");
        RADAR = b.comment("Show creatures on the maps (needs the radar upgrade in the Atlas)").translation(t("radar_enabled"))
                .define("enabled", true);
        RADAR_PASSIVE = b.comment("Also show animals, villagers and pets (off = hostile mobs only)")
                .translation(t("radar_passive")).define("passive", true);
        RADAR_ITEMS = b.comment("Show items lying on the ground (needs the items page in the Atlas)")
                .translation(t("radar_items")).define("items", true);
        b.pop();

        b.translation(t("general")).push("general");
        REQUIRE_ATLAS = b.comment("The map only works while you carry an Atlas").translation(t("require_atlas"))
                .define("requireAtlas", true);
        SLOT_X = b.comment("Move the Atlas slot in the inventory sideways (if another mod covers it)")
                .translation(t("slot_x")).defineInRange("atlasSlotX", 0, -400, 400);
        SLOT_Y = b.comment("Move the Atlas slot in the inventory up / down (if another mod covers it)")
                .translation(t("slot_y")).defineInRange("atlasSlotY", 0, -400, 400);
        b.pop();

        SPEC = b.build();
    }

    private AtlasConfig() {}
}

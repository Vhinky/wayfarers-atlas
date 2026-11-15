package com.wayfarersatlas;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.Reader;

/**
 * The look of the Atlas from a resource pack: assets/wayfarers_atlas/atlas_style.json with colours as "RRGGBB"
 * (every key may be left out):
 * <pre>
 * {
 *   "frame_dark": "2B1D10",        the dark edge of frames
 *   "frame_wood": "5A3F22",        the wood of frames
 *   "frame_gold": "B8934A",        the gold inlay
 *   "frame_stud": "E8D29A",        the studs in the corners
 *   "parchment_top": "E3D1A6",     panels, top
 *   "parchment_bottom": "CDB57F",  panels, bottom
 *   "ink": "3A2410",               text on the parchment
 *   "map_background": "2B2217"     unexplored land (classic theme)
 * }
 * </pre>
 */
public final class Style {
    private static final ResourceLocation FILE = ResourceLocation.fromNamespaceAndPath(WayfarersAtlas.MODID, "atlas_style.json");
    private static final int MAP_BACKGROUND = 0xFF17222B; // ABGR, like the map textures

    /** Unexplored land of the classic theme (ABGR). */
    public static int mapBackground = MAP_BACKGROUND;

    /** Resource packs were (re)loaded: back to the usual look, then whatever the packs change. */
    public static void reload(ResourceManager rm) {
        Ui.DARK = 0xFF2B1D10;
        Ui.BROWN = 0xFF5A3F22;
        Ui.GOLD = 0xFFB8934A;
        Ui.GOLD_LIGHT = 0xFFE8D29A;
        Ui.PARCH_TOP = 0xFFE3D1A6;
        Ui.PARCH_BOT = 0xFFCDB57F;
        Ui.INK = 0xFF3A2410;
        mapBackground = MAP_BACKGROUND;
        var res = rm.getResource(FILE);
        if (res.isPresent()) {
            try (Reader r = res.get().openAsReader()) {
                JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
                Ui.DARK = argb(o, "frame_dark", Ui.DARK);
                Ui.BROWN = argb(o, "frame_wood", Ui.BROWN);
                Ui.GOLD = argb(o, "frame_gold", Ui.GOLD);
                Ui.GOLD_LIGHT = argb(o, "frame_stud", Ui.GOLD_LIGHT);
                Ui.PARCH_TOP = argb(o, "parchment_top", Ui.PARCH_TOP);
                Ui.PARCH_BOT = argb(o, "parchment_bottom", Ui.PARCH_BOT);
                Ui.INK = argb(o, "ink", Ui.INK);
                if (o.has("map_background")) {
                    int c = argb(o, "map_background", 0xFF2B2217);
                    mapBackground = MapThemes.abgr((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF);
                }
            } catch (Exception e) {
                LogUtils.getLogger().warn("Wayfarer's Atlas: could not read {}", FILE, e);
            }
        }
        MapCache.version++; // the maps are drawn again with the new background
    }

    private static int argb(JsonObject o, String key, int def) {
        if (!o.has(key)) return def;
        String s = o.get(key).getAsString().trim();
        if (s.startsWith("#")) s = s.substring(1);
        return 0xFF000000 | (Integer.parseInt(s, 16) & 0xFFFFFF);
    }

    private Style() {}
}

package com.wayfarersatlas;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.List;

/**
 * Biome ids <-> small numbers (so the map can store a biome per 4x4 blocks cheaply) and readable names.
 * Works with every biome mod (Biomes O' Plenty, Terralith, ...): names come from the mod's own translations
 * ("biome.<mod>.<name>"), and if a mod has none, the id is turned into words.
 */
public final class BiomeNames {
    private static final List<String> IDS = new ArrayList<>();
    private static final Object2IntOpenHashMap<String> INDEX = new Object2IntOpenHashMap<>();

    static {
        INDEX.defaultReturnValue(-1);
        IDS.add(""); // 0 = unknown
        INDEX.put("", 0);
    }

    public static int index(String id) {
        int i = INDEX.getInt(id);
        if (i < 0) {
            i = IDS.size();
            IDS.add(id);
            INDEX.put(id, i);
        }
        return i;
    }

    public static String id(int index) {
        return index > 0 && index < IDS.size() ? IDS.get(index) : "";
    }

    public static String idOf(Holder<Biome> biome) {
        return biome.unwrapKey().map(k -> k.location().toString()).orElse("");
    }

    /** Biome where the given block is (only for loaded chunks). */
    public static String at(Level level, BlockPos pos) {
        return idOf(level.getBiome(pos));
    }

    /** Readable, translated name of a biome id, or "" if unknown. */
    public static String name(String id) {
        if (id == null || id.isEmpty()) return "";
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return id;
        String key = "biome." + rl.getNamespace() + "." + rl.getPath().replace('/', '.');
        if (I18n.exists(key)) return I18n.get(key);
        String path = rl.getPath();
        int slash = path.lastIndexOf('/');
        if (slash >= 0) path = path.substring(slash + 1);
        StringBuilder sb = new StringBuilder();
        for (String w : path.split("_")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    private BiomeNames() {}
}

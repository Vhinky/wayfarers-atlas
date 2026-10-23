package com.wayfarersatlas;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the map from loaded chunks. Colours come from block textures (with the game's own biome tint, so every
 * Biomes O' Plenty / modded biome keeps its colours), lit from the north-west. Underground the map switches to a
 * cave slice at the player's height, like Xaero's cave mode.
 */
public final class MapScanner {
    private static final int DEFAULT_WATER = 0x3F76E4;
    private static final int LAVA = 0xD2682C;
    private static final int NEAR = 13;
    public static final int LAYER = 6; // cave slice height

    private static final List<int[]> OFFSETS = new ArrayList<>();
    private static int builtRadius = -1;
    private static int nearCursor = 0, farCursor = 0;

    public enum CaveMode { AUTO, OFF, MANUAL }

    private static CaveMode mode = CaveMode.AUTO;
    private static int manualY = 0;
    private static boolean cave = false;
    private static int caveLayer = 0;
    private static int underCount = 0, surfCount = 0;

    public static boolean isCave() {
        return cave;
    }

    public static CaveMode mode() {
        return mode;
    }

    public static int manualY() {
        return manualY;
    }

    public static void setManualY(int y) {
        manualY = y;
    }

    /** Sets the cave view mode (used for the configured start mode). */
    public static void setMode(CaveMode m, LocalPlayer p) {
        while (mode != m) cycleMode(p);
    }

    /** AUTO -> OFF -> MANUAL -> AUTO. */
    public static void cycleMode(LocalPlayer p) {
        CaveMode old = mode;
        mode = switch (mode) {
            case AUTO -> CaveMode.OFF;
            case OFF -> CaveMode.MANUAL;
            case MANUAL -> CaveMode.AUTO;
        };
        if (mode == CaveMode.MANUAL) manualY = p.getBlockY();
        if (old == CaveMode.MANUAL) MapCache.dropManual();
        underCount = 0;
        surfCount = 0;
        if (mode == CaveMode.AUTO) cave = false; // the timers decide again
    }

    public static String dimId(ClientLevel level) {
        return level.dimension().location().toString();
    }

    /** Key of the layer that is currently shown: the dimension, or dimension + cave slice. */
    public static String viewKey(ClientLevel level) {
        String d = dimId(level);
        if (!cave) return d;
        return mode == CaveMode.MANUAL ? d + "#m" + manualY : d + "#c" + caveLayer;
    }

    /** The height the cave slice is taken at: the player's height, or the slider value in manual mode. */
    private static int sliceY(LocalPlayer p) {
        return mode == CaveMode.MANUAL ? manualY : p.getBlockY();
    }

    /**
     * Automatic cave mode. Underground = no daylight at the player's head AND natural ground (stone, deepslate,
     * dirt, sand...) somewhere above. Leaves, wood and roofs do not count, so standing under a tree or in a house
     * keeps the surface map. Cave mode switches on after ~1.5 s underground and off after ~0.2 s outside.
     */
    public static void updateCave(ClientLevel level, LocalPlayer p) {
        boolean ceiling = level.dimensionType().hasCeiling();
        // in or below the bedrock floor (void): nothing to map there - show the surface instead (not in manual mode)
        if (!ceiling && mode != CaveMode.MANUAL && p.getBlockY() < level.getMinBuildHeight() + 5) {
            cave = false;
            underCount = 0;
            surfCount = 0;
            caveLayer = Math.floorDiv(p.getBlockY(), LAYER);
            return;
        }
        if (mode == CaveMode.MANUAL || ceiling) {
            cave = true;
        } else if (mode == CaveMode.OFF) {
            cave = false;
        } else {
            // checked every tick: going underground needs ~1.5 s, coming back to the surface only ~0.2 s
            if (covered(level, p)) {
                underCount++;
                surfCount = 0;
            } else {
                surfCount++;
                underCount = 0;
            }
            if (!cave && underCount >= 30) cave = true;
            else if (cave && surfCount >= 4) cave = false;
        }
        caveLayer = Math.floorDiv(p.getBlockY(), LAYER);
        if (cave && mode == CaveMode.MANUAL) MapCache.touchManual(viewKey(level));
    }

    private static boolean covered(ClientLevel level, LocalPlayer p) {
        BlockPos head = BlockPos.containing(p.getX(), p.getEyeY(), p.getZ());
        if (level.getBrightness(LightLayer.SKY, head) > 0) return false; // daylight reaches the player: surface
        int bx = head.getX(), bz = head.getZ();
        if (!level.hasChunk(bx >> 4, bz >> 4)) return false;
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = head.getY() + 1; y <= top; y++) {
            if (isGround(level.getBlockState(m.set(bx, y, bz)))) return true;
        }
        return false;
    }

    private static boolean isGround(BlockState st) {
        return st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES)
                || st.is(BlockTags.DIRT) || st.is(BlockTags.SAND) || st.is(BlockTags.TERRACOTTA)
                || st.is(Blocks.GRAVEL);
    }

    private static void build(int r) {
        OFFSETS.clear();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz <= (r + 1) * (r + 1)) OFFSETS.add(new int[]{dx, dz}); // chunks that are not loaded are skipped
            }
        }
        OFFSETS.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
        builtRadius = r;
        nearCursor = 0;
        farCursor = 0;
    }

    /** Called every client tick; spends at most ~2.5 ms scanning chunks. */
    public static void tick(ClientLevel level, LocalPlayer player, int radius) {
        if (radius != builtRadius) build(radius);
        String key = viewKey(level);
        int pcx = player.chunkPosition().x;
        int pcz = player.chunkPosition().z;
        long deadline = System.nanoTime() + 3_000_000L; // at most 3 ms per tick

        int[] near = OFFSETS.get(nearCursor++ % Math.min(NEAR, OFFSETS.size()));
        scanOffset(level, player, key, pcx + near[0], pcz + near[1]);

        int scanned = 0;
        for (int i = 0; i < OFFSETS.size() && scanned < 24 && System.nanoTime() < deadline; i++) {
            int[] o = OFFSETS.get(farCursor++ % OFFSETS.size());
            if (MapCache.has(key, pcx + o[0], pcz + o[1])) continue; // new chunks first
            if (scanOffset(level, player, key, pcx + o[0], pcz + o[1])) scanned++;
        }
        if (scanned == 0) { // everything known: slowly refresh one far chunk per tick
            int[] o = OFFSETS.get(farCursor++ % OFFSETS.size());
            scanOffset(level, player, key, pcx + o[0], pcz + o[1]);
        }
    }

    /** A chunk is only scanned once all eight neighbours are loaded, so the relief has no seams. */
    private static boolean scanOffset(ClientLevel level, LocalPlayer player, String key, int cx, int cz) {
        var src = level.getChunkSource();
        LevelChunk c = src.getChunkNow(cx, cz);
        if (c == null) return false;
        LevelChunk n = src.getChunkNow(cx, cz - 1), s = src.getChunkNow(cx, cz + 1);
        LevelChunk w = src.getChunkNow(cx - 1, cz), e = src.getChunkNow(cx + 1, cz);
        LevelChunk nw = src.getChunkNow(cx - 1, cz - 1), ne = src.getChunkNow(cx + 1, cz - 1);
        LevelChunk sw = src.getChunkNow(cx - 1, cz + 1), se = src.getChunkNow(cx + 1, cz + 1);
        if (n == null || s == null || w == null || e == null || nw == null || ne == null || sw == null || se == null) return false;
        int[][] r = scanChunk(level, c, n, s, w, e, nw, ne, sw, se, cave, sliceY(player));
        MapCache.put(key, cx, cz, r[0], r[1], r[2], r[3]);
        return true;
    }

    private static final class Col {
        int y;
        double shadeH; // height used for relief: trees and plants count only a little, so the ground shape shows
        BlockState state;
        boolean lily;
        boolean rock;
    }

    private static void top(ClientLevel level, LevelChunk chunk, int lx, int lz, boolean cave, int start,
                            int minY, BlockPos.MutableBlockPos pos, Col out) {
        int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
        int y = cave ? start : chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz); // Y of the top block
        out.state = null;
        out.lily = false;
        out.rock = false;
        out.y = minY;
        boolean first = true;
        while (y > minY) {
            pos.set(baseX + lx, y, baseZ + lz);
            BlockState st = chunk.getBlockState(pos);
            if (st.is(Blocks.LILY_PAD)) {
                out.lily = true;
                first = false;
                y--;
                continue;
            }
            if (st.getMapColor(level, pos) != MapColor.NONE) {
                out.y = y;
                out.state = st;
                out.rock = cave && first;
                out.shadeH = y;
                if (!cave && isFoliage(st) && !st.is(BlockTags.LEAVES) && !st.is(BlockTags.LOGS)) { // grass, flowers: use the ground
                    int gy = y - 1;
                    while (gy > minY && y - gy < 24) {
                        pos.set(baseX + lx, gy, baseZ + lz);
                        BlockState b = chunk.getBlockState(pos);
                        if (!isFoliage(b) && !b.isAir() && b.getMapColor(level, pos) != MapColor.NONE) break;
                        gy--;
                    }
                    out.shadeH = gy + Math.min(y - gy, 3);
                }
                return;
            }
            first = false;
            y--;
        }
    }

    private static boolean isFoliage(BlockState st) {
        return st.is(BlockTags.LEAVES) || st.is(BlockTags.LOGS) || st.is(BlockTags.REPLACEABLE_BY_TREES)
                || st.is(BlockTags.FLOWERS) || st.is(BlockTags.SAPLINGS) || st.is(Blocks.BAMBOO) || st.is(Blocks.VINE);
    }

    private static int[][] scanChunk(ClientLevel level, LevelChunk c, LevelChunk n, LevelChunk s, LevelChunk w,
                                     LevelChunk e, LevelChunk nw, LevelChunk ne, LevelChunk sw, LevelChunk se,
                                     boolean cave, int playerY) {
        BlockColors bc = Minecraft.getInstance().getBlockColors();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        int start = Math.min(playerY + 2, maxY);
        int baseX = c.getPos().getMinBlockX(), baseZ = c.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        Col[] cols = new Col[256];
        double[] hg = new double[18 * 18]; // relief heights with a 1-block border from the neighbouring chunks
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                Col col = new Col();
                top(level, c, lx, lz, cave, start, minY, pos, col);
                cols[(lz << 4) | lx] = col;
                hg[(lz + 1) * 18 + lx + 1] = col.shadeH;
            }
        }
        Col tmp = new Col();
        for (int i = 0; i < 16; i++) {
            top(level, n, i, 15, cave, start, minY, pos, tmp); hg[i + 1] = tmp.shadeH;
            top(level, s, i, 0, cave, start, minY, pos, tmp); hg[17 * 18 + i + 1] = tmp.shadeH;
            top(level, w, 15, i, cave, start, minY, pos, tmp); hg[(i + 1) * 18] = tmp.shadeH;
            top(level, e, 0, i, cave, start, minY, pos, tmp); hg[(i + 1) * 18 + 17] = tmp.shadeH;
        }
        top(level, nw, 15, 15, cave, start, minY, pos, tmp); hg[0] = tmp.shadeH;
        top(level, ne, 0, 15, cave, start, minY, pos, tmp); hg[17] = tmp.shadeH;
        top(level, sw, 15, 0, cave, start, minY, pos, tmp); hg[17 * 18] = tmp.shadeH;
        top(level, se, 0, 0, cave, start, minY, pos, tmp); hg[17 * 18 + 17] = tmp.shadeH;

        int[] colors = new int[256];
        int[] heights = new int[256];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int idx = (lz << 4) | lx;
                Col col = cols[idx];
                if (col.state == null) {
                    colors[idx] = 0;
                    heights[idx] = Integer.MIN_VALUE;
                    continue;
                }
                heights[idx] = col.rock ? Integer.MIN_VALUE : col.y;
                pos.set(baseX + lx, col.y, baseZ + lz);
                BlockState st = col.state;
                MapColor mc = st.getMapColor(level, pos);

                double shade = relief(hg, lx + 1, lz + 1, cave ? Integer.MIN_VALUE : level.getSeaLevel());

                int rgb;
                FluidState fs = st.getFluidState();
                boolean modFluid = !fs.isEmpty() && st.getBlock() instanceof LiquidBlock
                        && !fs.getType().isSame(Fluids.WATER) && !fs.getType().isSame(Fluids.LAVA);
                if (modFluid) {
                    // oil, fuel, acid... from mods: their own texture colour (many of them reuse water's map colour)
                    int fc = BlockColorCache.fluid(fs, level, pos);
                    if (fc < 0) fc = blockRgb(level, bc, st, pos);
                    int depth = 1;
                    for (int yy = col.y - 1; yy > minY && depth < 16; yy--) {
                        pos.set(baseX + lx, yy, baseZ + lz);
                        if (!c.getBlockState(pos).getFluidState().getType().isSame(fs.getType())) break;
                        depth++;
                    }
                    pos.set(baseX + lx, col.y, baseZ + lz);
                    rgb = scale(fc, clamp(1.06 - depth * 0.03, 0.78, 1.06));
                    shade = 1.0;
                } else if (mc == MapColor.WATER) {
                    int depth = 1;
                    int floorRgb = -1;
                    int yy = col.y - 1;
                    while (yy > minY && depth < 64) {
                        pos.set(baseX + lx, yy, baseZ + lz);
                        BlockState below = c.getBlockState(pos);
                        if (below.getMapColor(level, pos) == MapColor.WATER) {
                            depth++;
                            yy--;
                            continue;
                        }
                        floorRgb = blockRgb(level, bc, below, pos);
                        break;
                    }
                    pos.set(baseX + lx, col.y, baseZ + lz);
                    int tint = safeTint(bc, st, level, pos, 0);
                    rgb = waterRgb(depth, floorRgb, tint == -1 ? DEFAULT_WATER : tint);
                    if (col.lily) rgb = mix(rgb, 0x5F7C4A, 0.30);
                    shade = 1.0;
                } else if (st.getFluidState().is(FluidTags.LAVA)) {
                    rgb = LAVA;
                    shade = 1.0;
                } else {
                    rgb = blockRgb(level, bc, st, pos);
                }

                if (cave) {
                    if (col.rock) shade *= 0.42;
                    else shade *= clamp(1.0 - (start - col.y) * 0.03, 0.55, 1.0);
                }
                colors[idx] = toAbgr(soften(scale(rgb, shade)));
            }
        }
        // biome per 4x4 blocks, taken at the height that is shown (works for every biome mod)
        int[] biomes = new int[16];
        for (int qz = 0; qz < 4; qz++) {
            for (int qx = 0; qx < 4; qx++) {
                int lx = qx * 4 + 2, lz = qz * 4 + 2;
                Col col = cols[(lz << 4) | lx];
                int by = col.state == null ? start : Math.min(col.y + 1, maxY);
                if (cave && col.rock) by = start;
                try {
                    biomes[(qz << 2) | qx] = BiomeNames.index(BiomeNames.idOf(c.getNoiseBiome(
                            QuartPos.fromBlock(baseX + lx), QuartPos.fromBlock(by), QuartPos.fromBlock(baseZ + lz))));
                } catch (RuntimeException ex) {
                    biomes[(qz << 2) | qx] = 0;
                }
            }
        }
        // block light just above the shown block (torches, lanterns, lava, glowstone...) for the night view
        int[] light = new int[256];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                Col col = cols[(lz << 4) | lx];
                if (col.state == null) continue;
                pos.set(baseX + lx, Math.min(col.y + 1, maxY), baseZ + lz);
                int bl = level.getBrightness(LightLayer.BLOCK, pos);
                if (col.state.getLightEmission(level, pos.set(baseX + lx, col.y, baseZ + lz)) > bl) {
                    bl = col.state.getLightEmission(level, pos); // the shown block glows itself (lava, glowstone)
                }
                light[(lz << 4) | lx] = bl;
            }
        }
        return new int[][]{colors, heights, biomes, light};
    }

    /**
     * Crisp, block-by-block relief (the way Xaero's map does it): every block is compared with its north and west
     * neighbours. Higher than them -> a little brighter, lower -> a little darker. Each terrace step therefore
     * becomes a sharp one-pixel line, slopes facing any direction are visible, and nothing is blurred over
     * several blocks. Tree crowns use their real leaf heights, so every crown gets its bumpy, block-by-block
     * look; height differences are capped at 3 blocks, so a tree casts a neat edge, not a big shadow.
     * Higher ground is also very slightly lighter. sea = sea level, or Integer.MIN_VALUE in cave view.
     */
    private static double relief(double[] hg, int x, int z, int sea) {
        double c = hg[z * 18 + x];
        double dN = clamp(c - hg[(z - 1) * 18 + x], -3, 3);
        double dW = clamp(c - hg[z * 18 + x - 1], -3, 3);
        double shade = 1.0 + clamp((dN + dW) * 0.07, -0.24, 0.18);
        if (sea != Integer.MIN_VALUE) shade *= 1.0 + clamp((c - sea) / 200.0, -0.08, 0.10);
        shade = 1.0 + (shade - 1.0) * AtlasConfig.RELIEF.get() / 100.0;
        return clamp(shade, 0.5, 1.45);
    }

    private static int safeTint(BlockColors bc, BlockState st, ClientLevel level, BlockPos pos, int index) {
        try {
            return bc.getColor(st, level, pos, index);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Texture colour (biome-tinted where the block is tinted), or the vanilla map colour as a fallback. */
    private static int blockRgb(ClientLevel level, BlockColors bc, BlockState st, BlockPos pos) {
        BlockColorCache.Info info = BlockColorCache.get(st);
        if (info.base() >= 0) {
            int c = info.base();
            if (info.tint() >= 0) {
                int t = safeTint(bc, st, level, pos, info.tint());
                if (t != -1) return scale(multiply(c, t), 1.30);
            }
            return scale(c, 1.06);
        }
        MapColor mc = st.getMapColor(level, pos);
        int abgr = mc.calculateRGBColor(MapColor.Brightness.HIGH);
        int rgb = ((abgr & 0xFF) << 16) | (abgr & 0xFF00) | ((abgr >> 16) & 0xFF);
        int t = safeTint(bc, st, level, pos, 0);
        return t != -1 ? scale(t, 0.95) : rgb;
    }

    /** Shallow water is light and takes the biome's water hue; deep water fades to a dark version of it. */
    private static int waterRgb(int depth, int floorRgb, int tintRgb) {
        double t = 1.0 - Math.exp(-depth / 9.0);
        double tr = (tintRgb >> 16) & 0xFF, tg = (tintRgb >> 8) & 0xFF, tb = tintRgb & 0xFF;
        double sr = lerp(tr, 0xA8, 0.35), sg = lerp(tg, 0xE8, 0.35), sb = lerp(tb, 0xF4, 0.35);
        double r = lerp(sr, tr * 0.38, t), g = lerp(sg, tg * 0.38, t), b = lerp(sb, tb * 0.38, t);
        if (floorRgb >= 0 && depth <= 5) {
            double wt = (1.0 - depth / 6.0) * 0.45;
            r = lerp(r, (floorRgb >> 16) & 0xFF, wt);
            g = lerp(g, (floorRgb >> 8) & 0xFF, wt);
            b = lerp(b, floorRgb & 0xFF, wt);
        }
        return ((int) r << 16) | ((int) g << 8) | (int) b;
    }

    /** Takes the edge off very saturated colours (lava, cherry leaves, ...) so the map looks calm, not neon. */
    private static int soften(int rgb) {
        double r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max <= 0 || max == min) return rgb;
        double s = (max - min) / max;
        if (s <= 0.55) return rgb; // ordinary colours stay as they are (crisp)
        double s2 = s - (s - 0.55) * 0.55;
        double v2 = max * (1.0 - 0.12 * (s - 0.55));
        double min2 = v2 * (1.0 - s2);
        double k = (v2 - min2) / (max - min);
        return ((int) clamp(min2 + (r - min) * k, 0, 255) << 16)
                | ((int) clamp(min2 + (g - min) * k, 0, 255) << 8)
                | (int) clamp(min2 + (b - min) * k, 0, 255);
    }

    private static int multiply(int a, int b) {
        int r = (((a >> 16) & 0xFF) * ((b >> 16) & 0xFF)) / 255;
        int g = (((a >> 8) & 0xFF) * ((b >> 8) & 0xFF)) / 255;
        int bl = ((a & 0xFF) * (b & 0xFF)) / 255;
        return (r << 16) | (g << 8) | bl;
    }

    private static int scale(int rgb, double f) {
        int r = (int) Math.min(255, ((rgb >> 16) & 0xFF) * f);
        int g = (int) Math.min(255, ((rgb >> 8) & 0xFF) * f);
        int b = (int) Math.min(255, (rgb & 0xFF) * f);
        return (r << 16) | (g << 8) | b;
    }

    private static int mix(int a, int b, double t) {
        return ((int) lerp((a >> 16) & 0xFF, (b >> 16) & 0xFF, t) << 16)
                | ((int) lerp((a >> 8) & 0xFF, (b >> 8) & 0xFF, t) << 8)
                | (int) lerp(a & 0xFF, b & 0xFF, t);
    }

    private static int toAbgr(int rgb) {
        return 0xFF000000 | ((rgb & 0xFF) << 16) | (rgb & 0xFF00) | ((rgb >> 16) & 0xFF);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private MapScanner() {}
}

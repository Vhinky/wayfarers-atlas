package com.wayfarersatlas;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

/**
 * Draws the cached map into a GUI rectangle.
 * Every detail level (1 texel per block, per 2, 4, 8, 16 blocks) has its own texture. Each one is built big enough
 * for the most zoomed-out view of its level, so zooming in and out never has to rebuild it, and switching back to
 * a level that was used before is instant. Newly scanned chunks are patched in one by one (small uploads).
 * Nearest-neighbour sampling keeps everything razor sharp.
 * Night: the map darkens with the real time of day (and rain), and places lit by torches, lanterns, lava...
 * keep their colour with a warm glow - like looking at the world at night.
 */
public final class MapView {
    private static final int BG = 0xFF17222B;   // ABGR: unexplored area
    private static int counter = 0;

    private final Layer[] layers = new Layer[5];
    /**
     * How far (GUI pixels) the last drawn picture was moved to land on whole screen pixels. Things drawn on the map
     * (creatures, players, markers) add this, so they stay glued to the terrain instead of wobbling against it.
     */
    public double shiftX, shiftY;

    public MapView(int unused) {
        for (int i = 0; i < layers.length; i++) layers[i] = new Layer(i);
    }

    /** ppb = real screen pixels per block. 0 = one texel per block; each level halves the resolution. */
    public static int lodForPpb(double ppb) {
        if (ppb >= 1.0 - 1e-9) return 0;
        return Math.min(4, (int) Math.ceil(-Math.log(ppb) / Math.log(2) - 1e-6));
    }

    /** Draw the map. Caller sets up the scissor rectangle. bpp = blocks per GUI pixel. */
    public void render(GuiGraphics g, int x, int y, int w, int h, double cx, double cz,
                       double bpp, String key, int tick) {
        double sc = Minecraft.getInstance().getWindow().getGuiScale();
        int lod = lodForPpb(sc / bpp);
        shiftX = 0;
        shiftY = 0;
        layers[lod].render(g, x, y, w, h, cx, cz, bpp, key, sc, night(key), this);
    }

    /** 0 = full daylight, 1 = darkest night. Caves and worlds without a sky (Nether, End) are always shown lit. */
    public static float night(String key) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || key.contains("#") || !mc.level.dimensionType().hasSkyLight()
                || !AtlasConfig.NIGHT_VIEW.get()) return 0f;
        float sky = mc.level.getSkyDarken(1f); // 1.0 at noon, 0.2 at midnight (lower in storms)
        return Math.max(0f, Math.min(1f, (1.0f - sky) / 0.8f));
    }

    /** Warm, slightly brighter version of a map colour with the block light as alpha (ABGR). */
    private static int litColor(int abgr, int light) {
        if (light <= 0 || abgr == 0) return 0;
        int r = abgr & 0xFF, g = (abgr >> 8) & 0xFF, b = (abgr >> 16) & 0xFF;
        r = Math.min(255, (int) (r * 1.08 + 18));
        g = Math.min(255, (int) (g * 1.0 + 8));
        b = (int) (b * 0.85);
        int a = (int) (255 * Math.pow(Math.min(15, light) / 15.0, 1.4));
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private static final int BURN = 0xFF22456B; // ABGR: singed brown

    /** How far (blocks) the ragged edge eats into the map at a block: wavy, with a little jitter. */
    static double rag(int x, int z) {
        double wave = Math.sin(x * 0.41 + Math.sin(z * 0.23) * 2.1) * 0.5 + Math.sin(z * 0.37 + Math.sin(x * 0.19) * 1.7) * 0.5;
        int h = x * 73856093 ^ z * 19349663;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        double jitter = ((h >>> 8) & 0xFF) / 255.0;
        return 1.4 + 0.9 * wave + 0.8 * jitter;
    }

    static int burnt(int abgr, double t) {
        int r = (int) ((abgr & 0xFF) + ((BURN & 0xFF) - (abgr & 0xFF)) * t);
        int g = (int) (((abgr >> 8) & 0xFF) + (((BURN >> 8) & 0xFF) - ((abgr >> 8) & 0xFF)) * t);
        int b = (int) (((abgr >> 16) & 0xFF) + (((BURN >> 16) & 0xFF) - ((abgr >> 16) & 0xFF)) * t);
        return 0xFF000000 | (b << 16) | (g << 8) | r;
    }

    /** The colour at 50 % opacity over the unexplored background. */
    private static int half(int abgr) {
        int r = ((abgr & 0xFF) + (BG & 0xFF)) >> 1;
        int g = (((abgr >> 8) & 0xFF) + ((BG >> 8) & 0xFF)) >> 1;
        int b = (((abgr >> 16) & 0xFF) + ((BG >> 16) & 0xFF)) >> 1;
        return 0xFF000000 | (b << 16) | (g << 8) | r;
    }

    private static final class Layer {
        final int lod;
        DynamicTexture tex, lit;
        ResourceLocation id, litId;
        int allocW, allocH;
        boolean valid;
        int ox, oz, regionW, regionH;
        int version = -1;
        long seen = 0;
        String key = "";
        int dx0, dy0, dx1, dy1; // dirty rectangle of the current patch

        Layer(int lod) {
            this.lod = lod;
        }

        void render(GuiGraphics g, int x, int y, int w, int h, double cx, double cz, double bpp, String key, double sc,
                    float night, MapView owner) {
            int step = 1 << lod;
            double f = step / bpp;

            double u0 = (cx - w * bpp / 2.0) / step, u1 = (cx + w * bpp / 2.0) / step;
            double v0 = (cz - h * bpp / 2.0) / step, v1 = (cz + h * bpp / 2.0) / step;

            boolean covered = valid && key.equals(this.key)
                    && Math.floor(u0) >= ox + 1 && Math.ceil(u1) <= ox + regionW - 1
                    && Math.floor(v0) >= oz + 1 && Math.ceil(v1) <= oz + regionH - 1;
            if (!covered || version != MapCache.version) {
                // size for the widest view of this level (its smallest zoom), so zooming stays inside it
                double ppbMin = lod == 0 ? 1.0 : 1.0 / step;
                double bppMax = Math.max(bpp, sc / ppbMin);
                double ew = w * bppMax / step, eh = h * bppMax / step;
                int margin = 48 + (int) (0.2 * Math.max(ew, eh));
                double mu = cx / step, mv = cz / step;
                fill(key, mu - ew / 2, mu + ew / 2, mv - eh / 2, mv + eh / 2, margin);
            } else if (seen != MapCache.seq()) {
                patch();
            }
            if (!valid || tex == null) return;

            double left = x + w / 2.0 + (ox * (double) step - cx) / bpp;
            double top = y + h / 2.0 + (oz * (double) step - cz) / bpp;
            double fr = f * sc; // real pixels per texel
            if (Math.abs(fr - Math.rint(fr)) < 1e-4) { // whole pixels per texel: snap to the pixel grid, stays crisp
                double l2 = Math.round(left * sc) / sc, t2 = Math.round(top * sc) / sc;
                owner.shiftX = l2 - left;
                owner.shiftY = t2 - top;
                left = l2;
                top = t2;
            }

            Ui.blendForFade(); // while the minimap fades, the map picture must fade with the frame
            g.pose().pushPose();
            g.pose().translate((float) left, (float) top, 0f);
            g.pose().scale((float) f, (float) f, 1f);
            g.blit(id, 0, 0, regionW, regionH, 0f, 0f, regionW, regionH, allocW, allocH);
            g.pose().popPose();

            if (night > 0.01f) {
                // darken everything, then bring back the places lit by torches & co with a warm glow
                int a = (int) (night * 0.72f * 255);
                g.fill(x, y, x + w, y + h, (a << 24) | 0x081020);
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                g.setColor(1f, 1f, 1f, night * 0.95f * Ui.alpha);
                g.pose().pushPose();
                g.pose().translate((float) left, (float) top, 0f);
                g.pose().scale((float) f, (float) f, 1f);
                g.blit(litId, 0, 0, regionW, regionH, 0f, 0f, regionW, regionH, allocW, allocH);
                g.pose().popPose();
                g.setColor(1f, 1f, 1f, Ui.alpha);
            }
        }

        void ensureAlloc(int w, int h) {
            if (tex != null && allocW >= w && allocH >= h) return;
            if (tex != null) Minecraft.getInstance().getTextureManager().release(id);
            if (lit != null) Minecraft.getInstance().getTextureManager().release(litId);
            allocW = ((Math.max(w, 64) + 63) / 64) * 64;
            allocH = ((Math.max(h, 64) + 63) / 64) * 64;
            tex = new DynamicTexture(allocW, allocH, false);
            lit = new DynamicTexture(allocW, allocH, false);
            int n = counter++;
            id = ResourceLocation.fromNamespaceAndPath(WayfarersAtlas.MODID, "view_" + n);
            litId = ResourceLocation.fromNamespaceAndPath(WayfarersAtlas.MODID, "view_lit_" + n);
            Minecraft.getInstance().getTextureManager().register(id, tex);
            Minecraft.getInstance().getTextureManager().register(litId, lit);
            valid = false;
        }

        /** Writes one chunk into the texture (clipped to the region) and grows the dirty rectangle. */
        void drawChunk(NativeImage img, NativeImage limg, int[][] lods, int tileX, int tileZ) {
            int tile = 16 >> lod;
            int bx = tileX * tile - ox, bz = tileZ * tile - oz;
            if (bx >= regionW || bz >= regionH || bx + tile <= 0 || bz + tile <= 0) return;
            int[] arr = lods[lod];
            int[] lights = lods.length > 7 + lod ? lods[7 + lod] : null;
            boolean faded = MapCache.isFaded(lods); // from a map table, not visited yet: 50 % see-through
            // burnt edges: next to unexplored land the map darkens and ends in a ragged edge, like singed paper
            var all = MapCache.chunks(key);
            boolean burn = all != null && AtlasConfig.BURNT_EDGES.get();
            boolean mN = burn && !all.containsKey(ChunkPos.asLong(tileX, tileZ - 1));
            boolean mS = burn && !all.containsKey(ChunkPos.asLong(tileX, tileZ + 1));
            boolean mW = burn && !all.containsKey(ChunkPos.asLong(tileX - 1, tileZ));
            boolean mE = burn && !all.containsKey(ChunkPos.asLong(tileX + 1, tileZ));
            boolean mNW = burn && !all.containsKey(ChunkPos.asLong(tileX - 1, tileZ - 1));
            boolean mNE = burn && !all.containsKey(ChunkPos.asLong(tileX + 1, tileZ - 1));
            boolean mSW = burn && !all.containsKey(ChunkPos.asLong(tileX - 1, tileZ + 1));
            boolean mSE = burn && !all.containsKey(ChunkPos.asLong(tileX + 1, tileZ + 1));
            boolean edge = mN || mS || mW || mE || mNW || mNE || mSW || mSE;
            int step = 1 << lod;
            for (int j = 0; j < tile; j++) {
                int v = bz + j;
                if (v < 0 || v >= regionH) continue;
                for (int i = 0; i < tile; i++) {
                    int u = bx + i;
                    if (u < 0 || u >= regionW) continue;
                    int c = arr[j * tile + i];
                    if (edge && c != 0) {
                        double bxl = i * step + step / 2.0, bzl = j * step + step / 2.0, d = 99;
                        if (mW) d = Math.min(d, bxl);
                        if (mE) d = Math.min(d, 16 - bxl);
                        if (mN) d = Math.min(d, bzl);
                        if (mS) d = Math.min(d, 16 - bzl);
                        if (mNW) d = Math.min(d, Math.hypot(bxl, bzl));
                        if (mNE) d = Math.min(d, Math.hypot(16 - bxl, bzl));
                        if (mSW) d = Math.min(d, Math.hypot(bxl, 16 - bzl));
                        if (mSE) d = Math.min(d, Math.hypot(16 - bxl, 16 - bzl));
                        if (d < 7) {
                            double r = rag(tileX * 16 + (int) bxl, tileZ * 16 + (int) bzl);
                            if (d < r) c = 0;
                            else if (d < r + 3) c = burnt(c, 0.85 * (1 - (d - r) / 3));
                        }
                    }
                    if (c != 0 && faded) c = half(c);
                    img.setPixelRGBA(u, v, c == 0 ? BG : c);
                    int lc = lights == null ? 0 : litColor(c, lights[j * tile + i]);
                    if (faded) lc = ((lc >>> 25) << 24) | (lc & 0xFFFFFF);
                    limg.setPixelRGBA(u, v, lc);
                }
            }
            dx0 = Math.max(0, Math.min(dx0, bx));
            dy0 = Math.max(0, Math.min(dy0, bz));
            dx1 = Math.min(regionW, Math.max(dx1, bx + tile));
            dy1 = Math.min(regionH, Math.max(dy1, bz + tile));
        }

        void fill(String key, double u0, double u1, double v0, double v1, int margin) {
            this.key = key;
            ox = (int) Math.floor(u0) - margin;
            oz = (int) Math.floor(v0) - margin;
            regionW = (int) (Math.ceil(u1) - Math.floor(u0)) + 1 + 2 * margin;
            regionH = (int) (Math.ceil(v1) - Math.floor(v0)) + 1 + 2 * margin;
            ensureAlloc(regionW, regionH);
            version = MapCache.version;
            seen = MapCache.seq();

            NativeImage img = tex.getPixels();
            NativeImage limg = lit.getPixels();
            if (img == null || limg == null) return;
            img.fillRect(0, 0, regionW, regionH, BG);
            limg.fillRect(0, 0, regionW, regionH, 0);

            Long2ObjectOpenHashMap<int[][]> chunks = MapCache.chunks(key);
            if (chunks != null && !chunks.isEmpty()) {
                int tile = 16 >> lod;
                int tx0 = Math.floorDiv(ox, tile), tx1 = Math.floorDiv(ox + regionW - 1, tile);
                int tz0 = Math.floorDiv(oz, tile), tz1 = Math.floorDiv(oz + regionH - 1, tile);
                long area = (long) (tx1 - tx0 + 1) * (tz1 - tz0 + 1);
                if (area <= chunks.size()) { // small view: look up every chunk in it
                    for (int tz = tz0; tz <= tz1; tz++) {
                        for (int tx = tx0; tx <= tx1; tx++) {
                            int[][] l = chunks.get(ChunkPos.asLong(tx, tz));
                            if (l != null) drawChunk(img, limg, l, tx, tz);
                        }
                    }
                } else { // huge view (zoomed far out): walk the explored chunks instead of empty space
                    for (Long2ObjectMap.Entry<int[][]> e : chunks.long2ObjectEntrySet()) {
                        long k = e.getLongKey();
                        int tx = ChunkPos.getX(k), tz = ChunkPos.getZ(k);
                        if (tx < tx0 || tx > tx1 || tz < tz0 || tz > tz1) continue;
                        drawChunk(img, limg, e.getValue(), tx, tz);
                    }
                }
            }
            tex.upload();
            tex.setFilter(false, false); // nearest: razor-sharp pixels (upload resets the filter, so set it every time)
            lit.upload();
            lit.setFilter(false, false);
            valid = true;
        }

        /** Draws only the chunks scanned since this level was last shown and uploads just that part. */
        void patch() {
            NativeImage img = tex.getPixels();
            NativeImage limg = lit.getPixels();
            if (img == null || limg == null) return;
            Long2ObjectOpenHashMap<int[][]> chunks = MapCache.chunks(key);
            dx0 = Integer.MAX_VALUE;
            dy0 = Integer.MAX_VALUE;
            dx1 = Integer.MIN_VALUE;
            dy1 = Integer.MIN_VALUE;
            // a new chunk also changes the burnt edges of its neighbours: redraw them too
            it.unimi.dsi.fastutil.longs.LongOpenHashSet todo = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
            boolean ok = MapCache.changesSince(key, seen, k -> {
                int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
                for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) todo.add(ChunkPos.asLong(cx + dx, cz + dz));
            });
            if (ok) {
                var iter = todo.iterator();
                while (iter.hasNext()) {
                    long k = iter.nextLong();
                    int[][] l = chunks == null ? null : chunks.get(k);
                    if (l != null) drawChunk(img, limg, l, ChunkPos.getX(k), ChunkPos.getZ(k));
                }
            }
            seen = MapCache.seq();
            if (!ok) { // too much changed meanwhile: rebuild on the next frame
                valid = false;
                return;
            }
            if (dx1 <= dx0 || dy1 <= dy0) return;
            tex.bind();
            img.upload(0, dx0, dy0, dx0, dy0, dx1 - dx0, dy1 - dy0, false, false);
            tex.setFilter(false, false);
            lit.bind();
            limg.upload(0, dx0, dy0, dx0, dy0, dx1 - dx0, dy1 - dy0, false, false);
            lit.setFilter(false, false);
        }
    }
}

package com.wayfarersatlas;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayDeque;
import java.util.EnumSet;

/**
 * Client side of "/atlas reveal": the server sends chunks of land you have not been to; they are drawn into the
 * Atlas you carry exactly like land you mapped yourself (same colours, same relief), a little every tick. They
 * come in squares, each with a ring of chunks round it (the relief needs the neighbours); a complete square is
 * drawn, then forgotten.
 */
public final class RevealClient {
    private static final long BUDGET_NS = 10_000_000L, BUDGET_BEHIND_NS = 25_000_000L; // per tick; more when far behind

    /** What arrived, in order: a chunk (Net.RevealChunk) or a square to draw (int[] x0, z0, x1, z1). */
    private static final ArrayDeque<Object> QUEUE = new ArrayDeque<>();
    private static final Long2ObjectOpenHashMap<LevelChunk> CHUNKS = new Long2ObjectOpenHashMap<>();
    private static String dim = null;
    private static int[] drawing = null; // the square being drawn
    private static int drawX, drawZ;     // next chunk of it
    private static boolean finishing = false;
    private static int drawn = 0;

    public static void reset() {
        QUEUE.clear();
        CHUNKS.clear();
        dim = null;
        drawing = null;
        finishing = false;
        drawn = 0;
    }

    public static void onChunks(Net.RevealChunks p) {
        if (p.first() || !p.dim().equals(dim)) {
            reset();
            dim = p.dim();
        }
        QUEUE.addAll(p.chunks());
        if (p.draw().length == 4) QUEUE.add(p.draw());
        if (p.done()) finishing = true;
    }

    /** Every client tick: takes in the chunks that arrived and draws the squares that are complete. */
    public static void tick(Minecraft mc) {
        if (dim == null) return;
        ClientLevel level = mc.level;
        // it goes into the Atlas you carry, and only while you are in that world (the chunks are built for it)
        boolean usable = level != null && dim.equals(MapScanner.dimId(level)) && AtlasSync.active() != null && ClientEvents.hasAtlas();
        if (!usable) {
            QUEUE.clear();
            CHUNKS.clear();
            drawing = null;
            if (finishing) reset();
            return;
        }
        long deadline = System.nanoTime() + (QUEUE.size() > 1500 ? BUDGET_BEHIND_NS : BUDGET_NS);
        World world = new World(level);
        while (System.nanoTime() < deadline) {
            if (drawing != null) { // finish the square first, then take what came after it
                draw(level, world, drawX, drawZ);
                if (++drawX > drawing[2]) {
                    drawX = drawing[0];
                    if (++drawZ > drawing[3]) {
                        // keep the chunks along its edge for the next squares (no seams), forget the rest
                        int[] d = drawing;
                        drawing = null;
                        CHUNKS.keySet().removeIf(k -> {
                            int kx = ChunkPos.getX(k), kz = ChunkPos.getZ(k);
                            boolean edge = kx <= d[0] || kx >= d[2] || kz <= d[1] || kz >= d[3];
                            return !edge;
                        });
                        if (CHUNKS.size() > 1500) CHUNKS.clear(); // never more than a few squares' edges
                    }
                }
                continue;
            }
            Object next = QUEUE.poll();
            if (next == null) break;
            if (next instanceof int[] square) {
                drawing = square;
                drawX = square[0];
                drawZ = square[1];
            } else if (next instanceof Net.RevealChunk c && c.data().length > 0) {
                try {
                    CHUNKS.put(ChunkPos.asLong(c.x(), c.z()), build(level, c));
                } catch (RuntimeException e) { // a chunk that cannot be read is skipped
                    com.mojang.logging.LogUtils.getLogger().debug("Wayfarer's Atlas: revealed chunk skipped", e);
                }
            }
        }
        if (QUEUE.isEmpty() && drawing == null && finishing) {
            int n = drawn;
            reset();
            if (n > 0 && mc.player != null) mc.player.displayClientMessage(Component.translatable("msg.wayfarers_atlas.revealed"), true);
        }
    }

    /** A chunk of its own (not part of the world) from the sections the server sent. */
    private static LevelChunk build(ClientLevel level, Net.RevealChunk c) {
        LevelChunk chunk = new LevelChunk(level, new ChunkPos(c.x(), c.z()));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(c.data()));
        int n = Math.min(buf.readVarInt(), chunk.getSections().length);
        for (int i = 0; i < n; i++) {
            if (buf.readBoolean()) chunk.getSection(i).read(buf);
        }
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE));
        return chunk;
    }

    private static void draw(ClientLevel level, World world, int cx, int cz) {
        LevelChunk c = CHUNKS.get(ChunkPos.asLong(cx, cz));
        if (c == null) return;
        String key = dim; // the surface map of that world
        if (MapCache.hasOwn(key, cx, cz)) return; // you mapped it yourself: that is better (it has the lights)
        LevelChunk[] nine = new LevelChunk[9];
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                LevelChunk n = CHUNKS.get(ChunkPos.asLong(cx + dx, cz + dz));
                nine[(dz + 1) * 3 + dx + 1] = n; // missing (never generated): the scanner uses the chunk's own edge
            }
        }
        world.center = c;
        try {
            int[][] r = MapScanner.scanRevealed(world, level.getSeaLevel(), nine);
            MapCache.putRevealed(key, cx, cz, r[0], r[1], r[2], r[3]);
            drawn++;
        } catch (RuntimeException e) {
            com.mojang.logging.LogUtils.getLogger().debug("Wayfarer's Atlas: revealed chunk not drawn", e);
        }
    }

    /** The blocks and biome colours of the chunks that were sent, for the map scanner. */
    private static final class World implements BlockAndTintGetter {
        private final ClientLevel level;
        LevelChunk center;

        World(ClientLevel level) {
            this.level = level;
        }

        private LevelChunk chunk(BlockPos pos) {
            LevelChunk c = CHUNKS.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
            return c != null ? c : center;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            LevelChunk c = CHUNKS.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
            return c == null ? Blocks.AIR.defaultBlockState() : c.getBlockState(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public int getHeight() {
            return level.getHeight();
        }

        @Override
        public int getMinBuildHeight() {
            return level.getMinBuildHeight();
        }

        @Override
        public float getShade(Direction direction, boolean shade) {
            return 1f;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return level.getLightEngine();
        }

        @Override
        public int getBrightness(LightLayer type, BlockPos pos) {
            return 0; // no light is known there
        }

        /** The colour of grass, leaves and water from the biome the server sent for that place. */
        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            LevelChunk c = chunk(pos);
            if (c == null) return -1;
            return resolver.getColor(c.getNoiseBiome(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2).value(), pos.getX(), pos.getZ());
        }
    }

    private RevealClient() {}
}

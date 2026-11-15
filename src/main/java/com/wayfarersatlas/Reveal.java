package com.wayfarersatlas;

import io.netty.buffer.Unpooled;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * "/atlas reveal": the server reads the chunks of an area itself and sends the players what lies on the surface;
 * their game draws it into the Atlas they carry - no need to fly over the land. It starts in the middle and goes
 * outwards, in squares of 12x12 chunks (each sent with one more ring of chunks round it: the relief needs the
 * neighbours). Chunks that were never generated stay empty, or are generated first (much slower) when asked.
 * Done a little every tick, so the server keeps running.
 */
public final class Reveal {
    public static final int MAX_RADIUS = 3000;
    private static final int TILE = 12;                 // chunks across one square
    private static final int CHUNKS_PER_TICK = 8;       // the players' games have to keep up drawing them
    private static final int BYTES_PER_PACKET = 300_000;
    private static final long BUDGET_NS = 20_000_000L, BUDGET_GENERATE_NS = 35_000_000L;
    private static final int BELOW = 72; // blocks kept under the lowest surface block of a chunk (water depth, trees)

    private static final class Job {
        ServerLevel level;
        String dim;
        int x0, z0, x1, z1;          // the area, in chunks
        List<int[]> tiles;           // squares (x0, z0, x1, z1 in chunks), nearest to the middle first
        int tile = 0;                // the square being sent
        int x, z;                    // next chunk of it (with the ring round it)
        boolean generate, first = true;
        List<UUID> players = new ArrayList<>();
        CommandSourceStack source;
        int total, done, found;
    }

    private static final ArrayDeque<Job> JOBS = new ArrayDeque<>();
    private static int tick = 0;

    /** Starts revealing a square of blocks round a point to these players. Returns the number of chunks. */
    public static int start(CommandSourceStack source, ServerLevel level, int centerX, int centerZ, int radius,
                            Collection<ServerPlayer> players, boolean generate) {
        Job j = new Job();
        j.level = level;
        j.dim = level.dimension().location().toString();
        int cx = centerX >> 4, cz = centerZ >> 4;
        j.x0 = (centerX - radius) >> 4;
        j.z0 = (centerZ - radius) >> 4;
        j.x1 = (centerX + radius) >> 4;
        j.z1 = (centerZ + radius) >> 4;
        // squares laid so that the middle one has the centre in its middle
        int ox = cx - TILE / 2, oz = cz - TILE / 2;
        j.tiles = new ArrayList<>();
        for (int tz = Math.floorDiv(j.z0 - oz, TILE); tz <= Math.floorDiv(j.z1 - oz, TILE); tz++) {
            for (int tx = Math.floorDiv(j.x0 - ox, TILE); tx <= Math.floorDiv(j.x1 - ox, TILE); tx++) {
                int ax = Math.max(j.x0, ox + tx * TILE), az = Math.max(j.z0, oz + tz * TILE);
                int bx = Math.min(j.x1, ox + tx * TILE + TILE - 1), bz = Math.min(j.z1, oz + tz * TILE + TILE - 1);
                if (ax <= bx && az <= bz) j.tiles.add(new int[]{ax, az, bx, bz});
            }
        }
        j.tiles.sort(Comparator.comparingDouble(t -> {
            double dx = (t[0] + t[2]) / 2.0 - cx, dz = (t[1] + t[3]) / 2.0 - cz;
            return dx * dx + dz * dz;
        }));
        int[] t = j.tiles.get(0);
        j.x = t[0] - 1;
        j.z = t[1] - 1;
        j.generate = generate;
        j.source = source;
        for (ServerPlayer p : players) j.players.add(p.getUUID());
        j.total = (j.x1 - j.x0 + 1) * (j.z1 - j.z0 + 1);
        JOBS.add(j);
        return j.total;
    }

    /** Stops everything that is being revealed. Returns how many jobs were stopped. */
    public static int stop(MinecraftServer server) {
        int n = JOBS.size();
        for (Job j : JOBS) {
            Net.RevealChunks end = new Net.RevealChunks(j.dim, j.first, true, List.of(), new int[0]);
            for (ServerPlayer p : online(server, j)) PacketDistributor.sendToPlayer(p, end);
        }
        JOBS.clear();
        return n;
    }

    public static void clear() {
        JOBS.clear();
    }

    private static List<ServerPlayer> online(MinecraftServer server, Job j) {
        List<ServerPlayer> out = new ArrayList<>();
        for (UUID id : j.players) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) out.add(p);
        }
        return out;
    }

    /** Every server tick: read (or generate) a few more chunks and send them. */
    public static void tick(MinecraftServer server) {
        Job j = JOBS.peek();
        if (j == null) return;
        tick++;
        List<ServerPlayer> to = online(server, j);
        if (to.isEmpty()) { // everyone it was for has left
            JOBS.poll();
            return;
        }
        long deadline = System.nanoTime() + (j.generate ? BUDGET_GENERATE_NS : BUDGET_NS);
        List<Net.RevealChunk> batch = new ArrayList<>();
        int[] draw = new int[0];
        int bytes = 0;
        while (j.tile < j.tiles.size() && batch.size() < CHUNKS_PER_TICK && bytes < BYTES_PER_PACKET && System.nanoTime() < deadline) {
            int[] t = j.tiles.get(j.tile);
            boolean inner = j.x >= t[0] && j.x <= t[2] && j.z >= t[1] && j.z <= t[3];
            // the ring round the square is only read (for the relief), not generated
            byte[] data = read(j, j.x, j.z, j.generate && inner);
            batch.add(new Net.RevealChunk(j.x, j.z, data));
            bytes += data.length + 12;
            if (inner) {
                j.done++;
                if (data.length > 0) j.found++;
            }
            if (++j.x > t[2] + 1) {
                j.x = t[0] - 1;
                if (++j.z > t[3] + 1) { // the square is complete: the players draw it
                    draw = t;
                    if (++j.tile < j.tiles.size()) {
                        int[] n = j.tiles.get(j.tile);
                        j.x = n[0] - 1;
                        j.z = n[1] - 1;
                    }
                    break;
                }
            }
        }
        boolean done = j.tile >= j.tiles.size();
        if (!batch.isEmpty() || done) {
            Net.RevealChunks msg = new Net.RevealChunks(j.dim, j.first, done, batch, draw);
            j.first = false;
            for (ServerPlayer p : to) PacketDistributor.sendToPlayer(p, msg);
        }
        if (done) {
            JOBS.poll();
            int found = j.found, total = j.total;
            j.source.sendSuccess(() -> Component.translatableWithFallback("command.wayfarers_atlas.reveal_done",
                    "Map revealed: %s of %s chunks", found, total), true);
        } else if (tick % 40 == 0 && j.source.getEntity() instanceof ServerPlayer sp) {
            sp.displayClientMessage(Component.translatableWithFallback("command.wayfarers_atlas.reveal_progress",
                    "Revealing the map: %s%%", j.done * 100 / Math.max(1, j.total)), true);
        }
    }

    /** What the client needs of one chunk, or nothing if it was never generated (and is not to be generated). */
    private static byte[] read(Job j, int cx, int cz, boolean generate) {
        try {
            // EMPTY: the chunk is only read from the disk as it is, nothing is generated
            ChunkAccess ca = j.level.getChunkSource().getChunk(cx, cz, generate ? ChunkStatus.FULL : ChunkStatus.EMPTY, true);
            if (ca == null || !ca.getPersistedStatus().isOrAfter(ChunkStatus.FULL)) return new byte[0];
            int low = Integer.MAX_VALUE;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) low = Math.min(low, ca.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
            }
            int cut = low - BELOW;
            LevelChunkSection[] sections = ca.getSections();
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buf.writeVarInt(sections.length);
                for (int i = 0; i < sections.length; i++) {
                    // deep underground is left out: only the surface is drawn
                    boolean send = (ca.getSectionYFromSectionIndex(i) << 4) + 16 > cut;
                    buf.writeBoolean(send);
                    if (send) sections[i].write(buf);
                }
                byte[] out = new byte[buf.readableBytes()];
                buf.readBytes(out);
                return out;
            } finally {
                buf.release();
            }
        } catch (RuntimeException e) {
            return new byte[0];
        }
    }

    private Reveal() {}
}

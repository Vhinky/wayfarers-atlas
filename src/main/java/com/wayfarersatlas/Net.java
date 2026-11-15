package com.wayfarersatlas;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.*;

/**
 * Network messages. The client maps the world (only it knows the block textures and colours) and sends the
 * chunks it mapped to the server, which keeps them inside the Atlas. When you pick up an Atlas the server sends
 * its map back; map tables mix the records of several Atlases.
 */
public final class Net {
    private static final int UPLOAD_MAX = 16;      // chunks per message client -> server (those are limited to 32 KB)
    private static final int SEND_PER_PACKET = 48; // chunks per message server -> client
    private static final int SEND_PACKETS_PER_TICK = 4;

    // ------------------------------------------------------------------ chunk data

    /** One chunk on the wire. key / biomes are indices into the lists of the batch. */
    public record Chunk(int key, long pos, boolean faded, int[] colors, int[] heights, int[] biomes, byte[] light) {
        void write(FriendlyByteBuf buf) {
            buf.writeVarInt(key);
            buf.writeLong(pos);
            buf.writeBoolean(faded);
            for (int c : colors) buf.writeInt(c);
            for (int h : heights) buf.writeInt(h);
            for (int b : biomes) buf.writeVarInt(b);
            buf.writeBytes(light);
        }

        static Chunk read(FriendlyByteBuf buf, int keys, int biomeCount) {
            int key = buf.readVarInt();
            long pos = buf.readLong();
            boolean faded = buf.readBoolean();
            int[] c = new int[256], h = new int[256], b = new int[16];
            byte[] l = new byte[256];
            for (int i = 0; i < 256; i++) c[i] = buf.readInt();
            for (int i = 0; i < 256; i++) h[i] = buf.readInt();
            for (int i = 0; i < 16; i++) {
                int v = buf.readVarInt();
                b[i] = v >= 0 && v < biomeCount ? v : 0;
            }
            buf.readBytes(l);
            if (key < 0 || key >= keys) throw new IllegalArgumentException("bad map layer");
            return new Chunk(key, pos, faded, c, h, b, l);
        }
    }

    /** A bunch of chunks plus the layer names and biome names they use. */
    public static final class Batch {
        public final List<String> keys = new ArrayList<>();
        public final List<String> biomes = new ArrayList<>();
        public final List<Chunk> chunks = new ArrayList<>();
        private final Map<String, Integer> keyIdx = new HashMap<>(), biomeIdx = new HashMap<>();

        public int key(String k) {
            Integer i = keyIdx.get(k);
            if (i == null) {
                i = keys.size();
                keys.add(k);
                keyIdx.put(k, i);
            }
            return i;
        }

        public int biome(String b) {
            Integer i = biomeIdx.get(b);
            if (i == null) {
                i = biomes.size();
                biomes.add(b);
                biomeIdx.put(b, i);
            }
            return i;
        }

        void write(FriendlyByteBuf buf) {
            buf.writeVarInt(keys.size());
            for (String k : keys) buf.writeUtf(k, 256);
            buf.writeVarInt(biomes.size());
            for (String b : biomes) buf.writeUtf(b, 256);
            buf.writeVarInt(chunks.size());
            for (Chunk c : chunks) c.write(buf);
        }

        static Batch read(FriendlyByteBuf buf, int maxChunks) {
            Batch b = new Batch();
            int kn = buf.readVarInt();
            if (kn < 0 || kn > 256) throw new IllegalArgumentException("too many layers");
            for (int i = 0; i < kn; i++) b.keys.add(buf.readUtf(256));
            int bn = buf.readVarInt();
            if (bn < 0 || bn > 4096) throw new IllegalArgumentException("too many biomes");
            for (int i = 0; i < bn; i++) b.biomes.add(buf.readUtf(256));
            int cn = buf.readVarInt();
            if (cn < 0 || cn > maxChunks) throw new IllegalArgumentException("too many chunks");
            for (int i = 0; i < cn; i++) b.chunks.add(Chunk.read(buf, kn, bn));
            return b;
        }
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String name) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(WayfarersAtlas.MODID, name));
    }

    // ------------------------------------------------------------------ messages

    /** Client -> server: chunks I just mapped, for this Atlas. */
    public record Upload(String atlas, Batch batch) implements CustomPacketPayload {
        public static final Type<Upload> TYPE = payloadType("upload");
        public static final StreamCodec<FriendlyByteBuf, Upload> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.atlas, 64);
                    p.batch.write(buf);
                },
                buf -> new Upload(buf.readUtf(64), Batch.read(buf, UPLOAD_MAX)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client -> server: send me the map of this Atlas. */
    public record RequestMap(String atlas) implements CustomPacketPayload {
        public static final Type<RequestMap> TYPE = payloadType("request_map");
        public static final StreamCodec<FriendlyByteBuf, RequestMap> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeUtf(p.atlas, 64), buf -> new RequestMap(buf.readUtf(64)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> client: chunks of an Atlas map. done = the whole map asked for has arrived. */
    public record MapChunks(String atlas, boolean done, Batch batch) implements CustomPacketPayload {
        public static final Type<MapChunks> TYPE = payloadType("map_chunks");
        public static final StreamCodec<FriendlyByteBuf, MapChunks> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.atlas, 64);
                    buf.writeBoolean(p.done);
                    p.batch.write(buf);
                },
                buf -> new MapChunks(buf.readUtf(64), buf.readBoolean(), Batch.read(buf, 4096)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client -> server: exchange records with the map table at pos (Atlas in hand). */
    public record TableExchange(BlockPos pos, boolean share, boolean take, List<String> markers) implements CustomPacketPayload {
        public static final Type<TableExchange> TYPE = payloadType("table_exchange");
        public static final StreamCodec<FriendlyByteBuf, TableExchange> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeBlockPos(p.pos);
                    buf.writeBoolean(p.share);
                    buf.writeBoolean(p.take);
                    buf.writeVarInt(p.markers.size());
                    for (String m : p.markers) buf.writeUtf(m, 2000);
                },
                buf -> {
                    BlockPos pos = buf.readBlockPos();
                    boolean share = buf.readBoolean(), take = buf.readBoolean();
                    int n = buf.readVarInt();
                    if (n < 0 || n > 1000) throw new IllegalArgumentException("too many markers");
                    List<String> m = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) m.add(buf.readUtf(2000));
                    return new TableExchange(pos, share, take, m);
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> client: what the exchange did, plus the table's markers if they were asked for. */
    public record TableResult(int written, int received, int markersLeft, List<String> markers) implements CustomPacketPayload {
        public static final Type<TableResult> TYPE = payloadType("table_result");
        public static final StreamCodec<FriendlyByteBuf, TableResult> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.written);
                    buf.writeVarInt(p.received);
                    buf.writeVarInt(p.markersLeft);
                    buf.writeVarInt(p.markers.size());
                    for (String m : p.markers) buf.writeUtf(m, 2000);
                },
                buf -> {
                    int w = buf.readVarInt(), r = buf.readVarInt(), ml = buf.readVarInt();
                    int n = buf.readVarInt();
                    if (n < 0 || n > 5000) throw new IllegalArgumentException("too many markers");
                    List<String> m = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) m.add(buf.readUtf(2000));
                    return new TableResult(w, r, ml, m);
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client -> server: the markers of this Atlas (sent in parts: first part clears, last part saves). */
    public record SetMarkers(String atlas, boolean first, boolean last, List<String> markers) implements CustomPacketPayload {
        public static final Type<SetMarkers> TYPE = payloadType("set_markers");
        public static final StreamCodec<FriendlyByteBuf, SetMarkers> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.atlas, 64);
                    buf.writeBoolean(p.first);
                    buf.writeBoolean(p.last);
                    writeList(buf, p.markers);
                },
                buf -> new SetMarkers(buf.readUtf(64), buf.readBoolean(), buf.readBoolean(), readList(buf, 1000)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> client: the markers kept inside this Atlas. */
    public record AtlasMarkers(String atlas, List<String> markers) implements CustomPacketPayload {
        public static final Type<AtlasMarkers> TYPE = payloadType("atlas_markers");
        public static final StreamCodec<FriendlyByteBuf, AtlasMarkers> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.atlas, 64);
                    writeList(buf, p.markers);
                },
                buf -> new AtlasMarkers(buf.readUtf(64), readList(buf, 5000)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> client: what the server allows (see ServerConfig.flags) and the worlds where the map does not work. */
    public record Rules(int flags, List<String> offDims) implements CustomPacketPayload {
        public static final Type<Rules> TYPE = payloadType("rules");
        public static final StreamCodec<FriendlyByteBuf, Rules> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.flags);
                    writeList(buf, p.offDims);
                },
                buf -> new Rules(buf.readVarInt(), readList(buf, 256)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> client: the server's markers and regions this player may see (see WorldMarks.send). */
    public record ServerMarksMsg(List<String> codes) implements CustomPacketPayload {
        public static final Type<ServerMarksMsg> TYPE = payloadType("server_marks");
        public static final StreamCodec<FriendlyByteBuf, ServerMarksMsg> CODEC = StreamCodec.of(
                (buf, p) -> writeList(buf, p.codes), buf -> new ServerMarksMsg(readList(buf, 5000)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> client: a marker or region (its code) for the Atlas you carry, from /atlas ... me / player. */
    public record GiveMark(String code) implements CustomPacketPayload {
        public static final Type<GiveMark> TYPE = payloadType("give_mark");
        public static final StreamCodec<FriendlyByteBuf, GiveMark> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeUtf(p.code, 2048), buf -> new GiveMark(buf.readUtf(2048)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> client: the player's FTB team ("" = none) and its markers / regions ("uid|code"). */
    public record TeamState(String team, List<String> codes) implements CustomPacketPayload {
        public static final Type<TeamState> TYPE = payloadType("team_state");
        public static final StreamCodec<FriendlyByteBuf, TeamState> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.team, 64);
                    writeList(buf, p.codes);
                },
                buf -> new TeamState(buf.readUtf(64), readList(buf, 1000)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client -> server: add / change (code) or remove a team marker or region. */
    public record TeamEdit(String uid, String code, boolean remove) implements CustomPacketPayload {
        public static final Type<TeamEdit> TYPE = payloadType("team_edit");
        public static final StreamCodec<FriendlyByteBuf, TeamEdit> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.uid, 16);
                    buf.writeUtf(p.code, 2048);
                    buf.writeBoolean(p.remove);
                },
                buf -> new TeamEdit(buf.readUtf(16), buf.readUtf(2048), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A teammate: where they are. */
    public record Mate(java.util.UUID id, String name, String dim, double x, double z, float yaw) {}

    /** Server -> client: the teammates who are online (twice a second). */
    public record TeamMates(List<Mate> mates) implements CustomPacketPayload {
        public static final Type<TeamMates> TYPE = payloadType("team_mates");
        public static final StreamCodec<FriendlyByteBuf, TeamMates> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.mates.size());
                    for (Mate m : p.mates) {
                        buf.writeUUID(m.id);
                        buf.writeUtf(m.name, 32);
                        buf.writeUtf(m.dim, 256);
                        buf.writeDouble(m.x);
                        buf.writeDouble(m.z);
                        buf.writeFloat(m.yaw);
                    }
                },
                buf -> {
                    int n = buf.readVarInt();
                    if (n < 0 || n > 200) throw new IllegalArgumentException("too many teammates");
                    List<Mate> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        list.add(new Mate(buf.readUUID(), buf.readUtf(32), buf.readUtf(256), buf.readDouble(), buf.readDouble(), buf.readFloat()));
                    }
                    return new TeamMates(list);
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** One chunk for "/atlas reveal": its sections as the server wrote them (empty = never generated). */
    public record RevealChunk(int x, int z, byte[] data) {}

    /**
     * Server -> client: chunks for the Atlas (/atlas reveal). first = a new area starts, done = it is finished,
     * draw = after these chunks, draw the chunks from (x0, z0) to (x1, z1) and forget the ones kept so far (or empty).
     */
    public record RevealChunks(String dim, boolean first, boolean done, List<RevealChunk> chunks, int[] draw) implements CustomPacketPayload {
        public static final Type<RevealChunks> TYPE = payloadType("reveal_chunks");
        public static final StreamCodec<FriendlyByteBuf, RevealChunks> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.dim, 256);
                    buf.writeBoolean(p.first);
                    buf.writeBoolean(p.done);
                    buf.writeVarInt(p.chunks.size());
                    for (RevealChunk c : p.chunks) {
                        buf.writeInt(c.x);
                        buf.writeInt(c.z);
                        buf.writeByteArray(c.data);
                    }
                    buf.writeVarIntArray(p.draw);
                },
                buf -> {
                    String dim = buf.readUtf(256);
                    boolean first = buf.readBoolean(), done = buf.readBoolean();
                    int n = buf.readVarInt();
                    if (n < 0 || n > 256) throw new IllegalArgumentException("too many chunks");
                    List<RevealChunk> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) list.add(new RevealChunk(buf.readInt(), buf.readInt(), buf.readByteArray(1 << 20)));
                    int[] draw = buf.readVarIntArray(4);
                    return new RevealChunks(dim, first, done, list, draw);
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> client: /atlas lead - lead the player to this place (or stop leading). */
    public record Lead(String name, String dim, int x, int y, int z, boolean clear) implements CustomPacketPayload {
        public static final Type<Lead> TYPE = payloadType("lead");
        public static final StreamCodec<FriendlyByteBuf, Lead> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.name, 64);
                    buf.writeUtf(p.dim, 256);
                    buf.writeVarInt(p.x);
                    buf.writeVarInt(p.y);
                    buf.writeVarInt(p.z);
                    buf.writeBoolean(p.clear);
                },
                buf -> new Lead(buf.readUtf(64), buf.readUtf(256), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client -> server: the Atlas slot in the inventory was clicked. */
    public record AtlasSlotClick(int action, int slot) implements CustomPacketPayload {
        public static final Type<AtlasSlotClick> TYPE = payloadType("atlas_slot_click");
        public static final StreamCodec<FriendlyByteBuf, AtlasSlotClick> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.action);
                    buf.writeVarInt(p.slot);
                },
                buf -> new AtlasSlotClick(buf.readVarInt(), buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> client: what is in your Atlas slot. */
    public record AtlasSlotSync(ItemStack stack) implements CustomPacketPayload {
        public static final Type<AtlasSlotSync> TYPE = payloadType("atlas_slot");
        public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, AtlasSlotSync> CODEC = StreamCodec.of(
                (buf, p) -> ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, p.stack),
                buf -> new AtlasSlotSync(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void sendRules(ServerPlayer p) {
        PacketDistributor.sendToPlayer(p, new Rules(ServerConfig.flags(), ServerConfig.offDims()));
    }

    private static void writeList(FriendlyByteBuf buf, List<String> list) {
        buf.writeVarInt(list.size());
        for (String s : list) buf.writeUtf(s, 2048);
    }

    private static List<String> readList(FriendlyByteBuf buf, int max) {
        int n = buf.readVarInt();
        if (n < 0 || n > max) throw new IllegalArgumentException("too many markers");
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(buf.readUtf(2048));
        return out;
    }

    public static void register(RegisterPayloadHandlersEvent e) {
        PayloadRegistrar r = e.registrar("2"); // 2: since 1.3.0 (server rules carry more, new messages)
        r.playToServer(Upload.TYPE, Upload.CODEC, (p, ctx) -> onUpload(p, ctx));
        r.playToServer(RequestMap.TYPE, RequestMap.CODEC, (p, ctx) -> onRequest(p, ctx));
        r.playToServer(TableExchange.TYPE, TableExchange.CODEC, (p, ctx) -> onExchange(p, ctx));
        r.playToServer(SetMarkers.TYPE, SetMarkers.CODEC, (p, ctx) -> onSetMarkers(p, ctx));
        r.playToClient(AtlasMarkers.TYPE, AtlasMarkers.CODEC, (p, ctx) -> AtlasSync.onMarkers(p));
        r.playToClient(Rules.TYPE, Rules.CODEC, (p, ctx) -> ServerRules.set(p.flags(), p.offDims()));
        r.playToClient(ServerMarksMsg.TYPE, ServerMarksMsg.CODEC, (p, ctx) -> ServerMarks.onList(p.codes()));
        r.playToClient(GiveMark.TYPE, GiveMark.CODEC, (p, ctx) -> ServerMarks.onGive(p.code()));
        r.playToClient(Lead.TYPE, Lead.CODEC, (p, ctx) -> ServerMarks.onLead(p));
        r.playToClient(RevealChunks.TYPE, RevealChunks.CODEC, (p, ctx) -> RevealClient.onChunks(p));
        r.playToClient(TeamState.TYPE, TeamState.CODEC, (p, ctx) -> TeamMarks.onState(p));
        r.playToClient(TeamMates.TYPE, TeamMates.CODEC, (p, ctx) -> TeamMarks.onMates(p));
        r.playToServer(TeamEdit.TYPE, TeamEdit.CODEC, (p, ctx) -> {
            if (ctx.player() instanceof ServerPlayer sp) TeamStore.edit(sp, p);
        });
        r.playToServer(AtlasSlotClick.TYPE, AtlasSlotClick.CODEC, (p, ctx) -> {
            if (ctx.player() instanceof ServerPlayer sp) AtlasSlot.click(sp, p.action(), p.slot());
        });
        r.playToClient(AtlasSlotSync.TYPE, AtlasSlotSync.CODEC, (p, ctx) -> AtlasSlot.clientStack = p.stack());
        // the client handlers live in a client-only class; these lambdas only touch it when a message arrives
        r.playToClient(MapChunks.TYPE, MapChunks.CODEC, (p, ctx) -> AtlasSync.onChunks(p));
        r.playToClient(TableResult.TYPE, TableResult.CODEC, (p, ctx) -> AtlasSync.onTableResult(p));
    }

    // ------------------------------------------------------------------ server side

    /** May this player read / write this map? Their own personal map, or an Atlas they carry. */
    private static boolean allowed(ServerPlayer p, String id) {
        if (id.equals("p_" + p.getUUID())) return true;
        if (!id.startsWith("a_")) return false;
        for (ItemStack s : AtlasCheck.all(p)) if (id.equals(AtlasCheck.mapId(s))) return true;
        return false;
    }

    private static boolean validKey(String key) {
        return !key.isEmpty() && key.length() <= 200 && !key.contains("#m"); // manual cave slices are not kept
    }

    private static void onUpload(Upload p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp) || !MapStore.running() || !allowed(sp, p.atlas)) return;
        MapStore.Data d = MapStore.get(p.atlas);
        int[] remap = new int[p.batch.biomes.size()];
        for (int i = 0; i < remap.length; i++) remap[i] = MapStore.biome(p.batch.biomes.get(i));
        for (Chunk c : p.batch.chunks) {
            String key = p.batch.keys.get(c.key());
            if (!validKey(key)) continue;
            int[] b = new int[16];
            for (int i = 0; i < 16; i++) b[i] = remap.length == 0 ? 0 : remap[c.biomes()[i]];
            d.layer(key).put(c.pos(), new MapStore.Rec(c.colors(), c.heights(), b, c.light(), false));
            d.dirty = true;
        }
    }

    private record Item(String key, long pos, MapStore.Rec rec) {}

    private static final class Stream {
        final String atlas;
        final ArrayDeque<Item> items;
        final boolean full; // the whole map was asked for: the last message says "done"

        Stream(String atlas, ArrayDeque<Item> items, boolean full) {
            this.atlas = atlas;
            this.items = items;
            this.full = full;
        }
    }

    private static final Map<UUID, ArrayDeque<Stream>> STREAMS = new HashMap<>();

    private static void onRequest(RequestMap p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp) || !MapStore.running() || !allowed(sp, p.atlas)) return;
        MapStore.Data d = MapStore.get(p.atlas);
        ArrayDeque<Item> items = new ArrayDeque<>();
        for (var layer : d.layers.entrySet()) {
            for (var e : layer.getValue().long2ObjectEntrySet()) items.add(new Item(layer.getKey(), e.getLongKey(), e.getValue()));
        }
        ArrayDeque<Stream> q = new ArrayDeque<>();
        q.add(new Stream(p.atlas, items, true)); // a new request replaces whatever was still being sent
        STREAMS.put(sp.getUUID(), q);
        PacketDistributor.sendToPlayer(sp, new AtlasMarkers(p.atlas, new ArrayList<>(d.markers)));
    }

    private static final Map<UUID, List<String>> PENDING_MARKERS = new HashMap<>();

    private static void onSetMarkers(SetMarkers p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp) || !MapStore.running() || !allowed(sp, p.atlas)) return;
        List<String> list = p.first ? new ArrayList<>() : PENDING_MARKERS.computeIfAbsent(sp.getUUID(), k -> new ArrayList<>());
        for (String m : p.markers) {
            boolean ok = (m.startsWith("atlas|") && m.length() <= 200) || (m.startsWith("ruler|") && m.length() <= 2000)
                    || (m.startsWith("region|") && m.length() <= 2000)
                    || (m.startsWith("shide|") && m.length() <= 48) // a server marker / region hidden in this Atlas
                    || (m.startsWith("folder|") && m.length() <= 64) // folders of the list of markers
                    || (m.startsWith("stroke|") && m.length() <= 2000) // pencil drawings
                    || ((m.startsWith("chron|") || m.startsWith("chronb|")) && m.length() <= 2000); // travel record
            if (list.size() < 2000 && ok) list.add(m);
        }
        if (!p.last) {
            PENDING_MARKERS.put(sp.getUUID(), list);
            return;
        }
        PENDING_MARKERS.remove(sp.getUUID());
        WaystonesServer.strip(list); // markers of destroyed waystones do not come back
        MapStore.Data d = MapStore.get(p.atlas);
        d.markers.clear();
        d.markers.addAll(list);
        d.dirty = true;
    }

    /** Called every server tick: sends a few more chunks to everyone who is receiving a map. */
    public static void pump(MinecraftServer server) {
        if (STREAMS.isEmpty()) return;
        for (Iterator<Map.Entry<UUID, ArrayDeque<Stream>>> it = STREAMS.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            ServerPlayer sp = server.getPlayerList().getPlayer(entry.getKey());
            ArrayDeque<Stream> q = entry.getValue();
            if (sp == null) {
                it.remove();
                continue;
            }
            for (int n = 0; n < SEND_PACKETS_PER_TICK && !q.isEmpty(); n++) {
                Stream s = q.peek();
                Batch b = new Batch();
                while (!s.items.isEmpty() && b.chunks.size() < SEND_PER_PACKET) {
                    Item item = s.items.poll();
                    int[] bio = new int[16];
                    for (int i = 0; i < 16; i++) bio[i] = b.biome(MapStore.biomeName(item.rec.biomes[i]));
                    b.chunks.add(new Chunk(b.key(item.key), item.pos, item.rec.faded, item.rec.colors,
                            item.rec.heights, bio, item.rec.light));
                }
                boolean last = s.items.isEmpty();
                if (last) q.poll();
                if (!b.chunks.isEmpty() || (last && s.full)) {
                    PacketDistributor.sendToPlayer(sp, new MapChunks(s.atlas, last && s.full, b));
                }
            }
            if (q.isEmpty()) it.remove();
        }
    }

    public static void forget(UUID player) {
        STREAMS.remove(player);
        PENDING_MARKERS.remove(player);
    }

    public static String tableId(ServerLevel level, BlockPos main) {
        return "t_" + level.dimension().location().toString().replaceAll("[^a-z0-9_.-]", "_")
                + "_" + main.getX() + "_" + main.getY() + "_" + main.getZ();
    }

    private static void onExchange(TableExchange p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp) || !MapStore.running()) return;
        if (!ServerConfig.MAP_TABLE.get()) {
            sp.displayClientMessage(net.minecraft.network.chat.Component.translatable("msg.wayfarers_atlas.table_disabled"), true);
            return;
        }
        ServerLevel level = sp.serverLevel();
        if (!level.isLoaded(p.pos)) return;
        BlockState st = level.getBlockState(p.pos);
        if (!(st.getBlock() instanceof MapTableBlock)) return;
        BlockPos main = MapTableBlock.mainPos(p.pos, st);
        if (sp.distanceToSqr(Vec3.atCenterOf(main)) > 8 * 8) return;

        ItemStack held = sp.getItemInHand(InteractionHand.MAIN_HAND);
        if (!held.is(WayfarersAtlas.ATLAS.get())) held = sp.getItemInHand(InteractionHand.OFF_HAND);
        if (!held.is(WayfarersAtlas.ATLAS.get())) held = AtlasSlot.get(sp); // empty hand: the Atlas in the Atlas slot
        if (!held.is(WayfarersAtlas.ATLAS.get())) return;
        if (held.get(WayfarersAtlas.MAP_ID.get()) == null) {
            held.set(WayfarersAtlas.MAP_ID.get(), UUID.randomUUID());
            if (held == AtlasSlot.get(sp)) AtlasSlot.sync(sp);
        }
        String atlasId = AtlasCheck.mapId(held);

        MapStore.Data atlas = MapStore.get(atlasId);
        MapStore.Data table = MapStore.get(tableId(level, main));

        // 1. what I explored myself goes onto the table
        int written = 0;
        for (var layer : atlas.layers.entrySet()) {
            var tl = table.layer(layer.getKey());
            for (var e : layer.getValue().long2ObjectEntrySet()) {
                MapStore.Rec r = e.getValue();
                if (r.faded) continue;
                MapStore.Rec old = tl.get(e.getLongKey());
                if (old == null || !old.sameData(r)) {
                    tl.put(e.getLongKey(), r.withFaded(false));
                    written++;
                }
            }
        }
        if (written > 0) table.dirty = true;

        // 2. what others left on the table and I have not seen goes into the Atlas, faded
        ArrayDeque<Item> received = new ArrayDeque<>();
        for (var layer : table.layers.entrySet()) {
            var al = atlas.layer(layer.getKey());
            for (var e : layer.getValue().long2ObjectEntrySet()) {
                MapStore.Rec mine = al.get(e.getLongKey());
                if (mine != null && (!mine.faded || mine.sameData(e.getValue()))) continue;
                MapStore.Rec f = e.getValue().withFaded(true);
                al.put(e.getLongKey(), f);
                received.add(new Item(layer.getKey(), e.getLongKey(), f));
            }
        }
        if (!received.isEmpty()) atlas.dirty = true;

        // 3. markers
        if (p.share) {
            for (String m : p.markers) {
                if (table.markers.size() >= 2000) break;
                boolean ok = (m.startsWith("atlas|") && m.length() <= 200) || (m.startsWith("region|") && m.length() <= 2000);
                if (ok && !WaystonesServer.gone(m) && table.markers.add(m)) table.dirty = true;
            }
        }
        List<String> give = p.take ? new ArrayList<>(table.markers) : List.of();

        if (!received.isEmpty()) {
            STREAMS.computeIfAbsent(sp.getUUID(), k -> new ArrayDeque<>()).add(new Stream(atlasId, received, false));
        }
        level.playSound(null, main, SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundSource.BLOCKS, 1f, 1f);
        PacketDistributor.sendToPlayer(sp, new TableResult(written, received.size(), table.markers.size(), give));
    }

    private Net() {}
}

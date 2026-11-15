package com.wayfarersatlas;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.ColumnPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ColumnPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /atlas (operators only), for server owners and modpack makers:
 * <pre>
 * /atlas region add &lt;id&gt; &lt;colour&gt; &lt;for&gt; &lt;x z&gt; &lt;x z&gt; &lt;x z&gt; [more x z] [name]
 * /atlas region remove|rename|secret|list
 * /atlas marker add &lt;id&gt; &lt;icon&gt; &lt;colour&gt; &lt;for&gt; &lt;x y z&gt; [name]
 * /atlas marker remove|rename|secret|list
 * /atlas lead &lt;players&gt; &lt;x y z&gt; [name]      /atlas lead &lt;players&gt; clear
 * /atlas reveal start &lt;radius&gt; [for &lt;players&gt;] [at &lt;x z&gt;] [generate]      /atlas reveal stop
 * </pre>
 * for = all (every Atlas, also of new players; players cannot delete it), all_removable (every Atlas gets its own
 * copy, players may change or delete it), me or a player's name (only into the Atlas that player carries).
 * Coordinates work like in /fill: TAB gives the block you look at, ~ is where you stand. The name comes last and
 * may have spaces and any letters. The world is the one the command is run in.
 */
public final class AtlasCommands {
    private static final int MAX_CORNERS = 30;
    private static final Map<String, Integer> COLORS = new LinkedHashMap<>();

    static {
        COLORS.put("red", 0xD9534F);
        COLORS.put("orange", 0xE8943A);
        COLORS.put("yellow", 0xE8C84A);
        COLORS.put("green", 0x6FAF4F);
        COLORS.put("cyan", 0x4FB3B0);
        COLORS.put("blue", 0x4F86D9);
        COLORS.put("purple", 0x9B6FD0);
        COLORS.put("pink", 0xE08AB4);
        COLORS.put("brown", 0x8A5A32);
        COLORS.put("white", 0xF2EBDD);
        COLORS.put("gray", 0x8C8C8C);
        COLORS.put("black", 0x2B2B2B);
    }

    private static Component msg(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("command.wayfarers_atlas." + key, fallback, args);
    }

    private static CommandSyntaxException fail(String key, String fallback, Object... args) {
        return new SimpleCommandExceptionType(msg(key, fallback, args)).create();
    }

    private static final SuggestionProvider<CommandSourceStack> COLOR_NAMES =
            (ctx, b) -> SharedSuggestionProvider.suggest(COLORS.keySet(), b);
    private static final SuggestionProvider<CommandSourceStack> FOR = (ctx, b) -> {
        List<String> s = new ArrayList<>(List.of("all", "all_removable", "me"));
        s.addAll(ctx.getSource().getOnlinePlayerNames());
        return SharedSuggestionProvider.suggest(s, b);
    };
    private static final SuggestionProvider<CommandSourceStack> REGION_IDS =
            (ctx, b) -> SharedSuggestionProvider.suggest(WorldMarks.REGIONS.keySet(), b);
    private static final SuggestionProvider<CommandSourceStack> MARKER_IDS =
            (ctx, b) -> SharedSuggestionProvider.suggest(WorldMarks.MARKERS.keySet(), b);

    public static void register(RegisterCommandsEvent e) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("atlas").requires(s -> s.hasPermission(2));

        root.then(Commands.literal("region")
                .then(Commands.literal("add").then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("name", StringArgumentType.string()).suggests(QUOTED_NAME)
                                .then(Commands.argument("color", StringArgumentType.word()).suggests(COLOR_NAMES)
                                        .then(Commands.argument("for", StringArgumentType.word()).suggests(FOR)
                                                .then(corner(1)))))))
                .then(Commands.literal("remove").then(Commands.argument("id", StringArgumentType.word()).suggests(REGION_IDS)
                        .executes(ctx -> {
                            String id = StringArgumentType.getString(ctx, "id");
                            if (WorldMarks.REGIONS.remove(id) == null) throw fail("no_region", "There is no server region \"%s\"", id);
                            WorldMarks.changed(ctx.getSource().getServer());
                            ctx.getSource().sendSuccess(() -> msg("region_removed", "Server region \"%s\" removed", id), true);
                            return 1;
                        })))
                .then(Commands.literal("rename").then(Commands.argument("id", StringArgumentType.word()).suggests(REGION_IDS)
                        .then(Commands.argument("name", StringArgumentType.greedyString()).executes(ctx -> {
                            String id = StringArgumentType.getString(ctx, "id");
                            WorldMarks.SRegion r = WorldMarks.REGIONS.get(id);
                            if (r == null) throw fail("no_region", "There is no server region \"%s\"", id);
                            String name = name(ctx);
                            WorldMarks.REGIONS.put(id, r.named(name));
                            WorldMarks.changed(ctx.getSource().getServer());
                            ctx.getSource().sendSuccess(() -> msg("renamed", "\"%s\" is now called \"%s\"", id, name), true);
                            return 1;
                        }))))
                .then(Commands.literal("secret").then(Commands.argument("id", StringArgumentType.word()).suggests(REGION_IDS)
                        .then(Commands.argument("secret", BoolArgumentType.bool()).executes(ctx -> {
                            String id = StringArgumentType.getString(ctx, "id");
                            WorldMarks.SRegion r = WorldMarks.REGIONS.get(id);
                            if (r == null) throw fail("no_region", "There is no server region \"%s\"", id);
                            boolean secret = BoolArgumentType.getBool(ctx, "secret");
                            WorldMarks.REGIONS.put(id, r.secret(secret));
                            WorldMarks.changed(ctx.getSource().getServer());
                            ctx.getSource().sendSuccess(() -> secretMsg(id, secret), true);
                            return 1;
                        }))))
                .then(Commands.literal("list").executes(ctx -> {
                    if (WorldMarks.REGIONS.isEmpty()) ctx.getSource().sendSuccess(() -> msg("no_regions", "No server regions yet"), false);
                    for (WorldMarks.SRegion r : WorldMarks.REGIONS.values()) {
                        int[] p = r.points().get(0);
                        ctx.getSource().sendSuccess(() -> Component.literal(r.id() + " - " + r.name() + " (" + r.dim() + ", "
                                + r.points().size() + " pts, from " + p[0] + " " + p[1] + ")" + tags(r.secret(), r.free())), false);
                    }
                    return WorldMarks.REGIONS.size();
                })));

        root.then(Commands.literal("marker")
                .then(Commands.literal("add").then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("name", StringArgumentType.string()).suggests(QUOTED_NAME)
                                .then(Commands.argument("icon", IntegerArgumentType.integer(0, Icons.COUNT + Icons.MAX_CUSTOM - 1))
                                        .then(Commands.argument("color", StringArgumentType.word()).suggests(COLOR_NAMES)
                                                .then(Commands.argument("for", StringArgumentType.word()).suggests(FOR)
                                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                                .executes(AtlasCommands::markerAdd))))))))
                .then(Commands.literal("remove").then(Commands.argument("id", StringArgumentType.word()).suggests(MARKER_IDS)
                        .executes(ctx -> {
                            String id = StringArgumentType.getString(ctx, "id");
                            if (WorldMarks.MARKERS.remove(id) == null) throw fail("no_marker", "There is no server marker \"%s\"", id);
                            WorldMarks.changed(ctx.getSource().getServer());
                            ctx.getSource().sendSuccess(() -> msg("marker_removed", "Server marker \"%s\" removed", id), true);
                            return 1;
                        })))
                .then(Commands.literal("rename").then(Commands.argument("id", StringArgumentType.word()).suggests(MARKER_IDS)
                        .then(Commands.argument("name", StringArgumentType.greedyString()).executes(ctx -> {
                            String id = StringArgumentType.getString(ctx, "id");
                            WorldMarks.SMarker m = WorldMarks.MARKERS.get(id);
                            if (m == null) throw fail("no_marker", "There is no server marker \"%s\"", id);
                            String name = name(ctx);
                            WorldMarks.MARKERS.put(id, m.named(name));
                            WorldMarks.changed(ctx.getSource().getServer());
                            ctx.getSource().sendSuccess(() -> msg("renamed", "\"%s\" is now called \"%s\"", id, name), true);
                            return 1;
                        }))))
                .then(Commands.literal("secret").then(Commands.argument("id", StringArgumentType.word()).suggests(MARKER_IDS)
                        .then(Commands.argument("secret", BoolArgumentType.bool()).executes(ctx -> {
                            String id = StringArgumentType.getString(ctx, "id");
                            WorldMarks.SMarker m = WorldMarks.MARKERS.get(id);
                            if (m == null) throw fail("no_marker", "There is no server marker \"%s\"", id);
                            boolean secret = BoolArgumentType.getBool(ctx, "secret");
                            WorldMarks.MARKERS.put(id, m.secret(secret));
                            WorldMarks.changed(ctx.getSource().getServer());
                            ctx.getSource().sendSuccess(() -> secretMsg(id, secret), true);
                            return 1;
                        }))))
                .then(Commands.literal("list").executes(ctx -> {
                    if (WorldMarks.MARKERS.isEmpty()) ctx.getSource().sendSuccess(() -> msg("no_markers", "No server markers yet"), false);
                    for (WorldMarks.SMarker m : WorldMarks.MARKERS.values()) {
                        ctx.getSource().sendSuccess(() -> Component.literal(m.id() + " - " + m.name() + " (" + m.dim() + ", "
                                + m.x() + " " + m.y() + " " + m.z() + ")" + tags(m.secret(), m.free())), false);
                    }
                    return WorldMarks.MARKERS.size();
                })));

        root.then(Commands.literal("lead").then(Commands.argument("players", EntityArgument.players())
                .then(Commands.literal("clear").executes(ctx -> {
                    Collection<ServerPlayer> players = EntityArgument.getPlayers(ctx, "players");
                    for (ServerPlayer p : players) PacketDistributor.sendToPlayer(p, new Net.Lead("", "", 0, 0, 0, true));
                    ctx.getSource().sendSuccess(() -> msg("lead_cleared", "Stopped leading %s player(s)", players.size()), true);
                    return players.size();
                }))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(ctx -> lead(ctx, ""))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> lead(ctx, name(ctx)))))));

        // /atlas reveal <radius>                          - your Atlas, round you
        // /atlas reveal <radius> generate                 - ... and land nobody has been to is generated first
        // /atlas reveal <radius> at <x z> [generate]      - round another place
        // /atlas reveal <radius> for <players> [at <x z>] [generate]  - into other players' Atlases
        // /atlas reveal start <radius> [for <players>] [at <x z>] [generate]     /atlas reveal stop
        root.then(Commands.literal("reveal")
                .then(Commands.literal("stop").executes(ctx -> {
                    int n = Reveal.stop(ctx.getSource().getServer());
                    ctx.getSource().sendSuccess(() -> msg("reveal_stopped", "Stopped revealing the map (%s)", n), true);
                    return n;
                }))
                .then(Commands.literal("start").then(Commands.argument("radius", IntegerArgumentType.integer(16, Reveal.MAX_RADIUS))
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(List.of("100", "250", "500", "1000"), b))
                        .executes(ctx -> reveal(ctx, false, false, false))
                        .then(Commands.literal("generate").executes(ctx -> reveal(ctx, false, false, true)))
                        .then(revealAt(false))
                        .then(Commands.literal("for").then(Commands.argument("players", EntityArgument.players())
                                .executes(ctx -> reveal(ctx, true, false, false))
                                .then(Commands.literal("generate").executes(ctx -> reveal(ctx, true, false, true)))
                                .then(revealAt(true)))))));

        e.getDispatcher().register(root);
    }

    /** The corners of a region, each like in /fill (TAB: the block you look at; ~ ~: where you stand); 3 or more. */
    private static RequiredArgumentBuilder<CommandSourceStack, ?> corner(int i) {
        RequiredArgumentBuilder<CommandSourceStack, ?> a = Commands.argument("corner" + i, ColumnPosArgument.columnPos());
        if (i < MAX_CORNERS) a.then(corner(i + 1));
        if (i >= 3) {
            final int n = i;
            a.executes(ctx -> regionAdd(ctx, n));
        }
        return a;
    }

    /** TAB on the name gives it in quotes (needed for spaces and Russian letters): "id", or the name it has now. */
    private static final SuggestionProvider<CommandSourceStack> QUOTED_NAME = (ctx, b) -> {
        String id = StringArgumentType.getString(ctx, "id").toLowerCase(Locale.ROOT);
        String now = null;
        if (WorldMarks.REGIONS.containsKey(id) && ctx.getNodes().get(1).getNode().getName().equals("region")) now = WorldMarks.REGIONS.get(id).name();
        if (WorldMarks.MARKERS.containsKey(id) && ctx.getNodes().get(1).getNode().getName().equals("marker")) now = WorldMarks.MARKERS.get(id).name();
        String s = now != null ? now : id;
        return SharedSuggestionProvider.suggest(List.of("\"" + s.replace("\\", "").replace("\"", "") + "\""), b);
    };

    private static LiteralArgumentBuilder<CommandSourceStack> revealAt(boolean players) {
        return Commands.literal("at").then(Commands.argument("center", ColumnPosArgument.columnPos())
                .executes(ctx -> reveal(ctx, players, true, false))
                .then(Commands.literal("generate").executes(ctx -> reveal(ctx, players, true, true))));
    }

    private static String tags(boolean secret, boolean free) {
        return (secret ? " [secret]" : "") + (free ? " [removable]" : "");
    }

    private static Component secretMsg(String id, boolean secret) {
        return secret ? msg("secret_on", "\"%s\" is now secret: players see it after they have been there", id)
                : msg("secret_off", "\"%s\" is shown to everyone again", id);
    }

    private static String name(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String name = WorldMarks.cleanName(StringArgumentType.getString(ctx, "name"));
        if (name.isEmpty()) throw fail("bad_name", "The name is empty");
        return name;
    }

    private static String id(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, "id").toLowerCase(Locale.ROOT);
        if (!WorldMarks.validId(id)) throw fail("bad_id", "The id may have only a-z, 0-9, _ - . (up to 32)");
        return id;
    }

    private static int color(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String c = StringArgumentType.getString(ctx, "color").toLowerCase(Locale.ROOT);
        Integer named = COLORS.get(c);
        if (named != null) return named;
        if (c.matches("[0-9a-f]{6}")) return Integer.parseInt(c, 16);
        throw fail("bad_color", "The colour is a name (%s) or RRGGBB, for example FF8800", String.join(", ", COLORS.keySet()));
    }

    /** for: "all", "all_removable", "me" or a player name. */
    private record Target(ServerPlayer player, boolean free) {}

    private static Target target(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String f = StringArgumentType.getString(ctx, "for");
        if (f.equalsIgnoreCase("all")) return new Target(null, false);
        if (f.equalsIgnoreCase("all_removable")) return new Target(null, true);
        if (f.equalsIgnoreCase("me")) return new Target(ctx.getSource().getPlayerOrException(), false);
        ServerPlayer p = ctx.getSource().getServer().getPlayerList().getPlayerByName(f);
        if (p == null) throw fail("no_player", "\"%s\" is not all, all_removable, me or a player who is online", f);
        return new Target(p, false);
    }

    private static int regionAdd(CommandContext<CommandSourceStack> ctx, int corners) throws CommandSyntaxException {
        String id = id(ctx), name = name(ctx);
        int color = color(ctx);
        Target to = target(ctx);
        List<int[]> pts = new ArrayList<>();
        for (int i = 1; i <= corners; i++) {
            ColumnPos c = ColumnPosArgument.getColumnPos(ctx, "corner" + i);
            pts.add(new int[]{c.x(), c.z()});
        }
        String dim = ctx.getSource().getLevel().dimension().location().toString();
        WorldMarks.SRegion r = new WorldMarks.SRegion(id, name, dim, color, pts, false, to.free());
        if (to.player() != null) { // into one player's Atlas, as their own region
            PacketDistributor.sendToPlayer(to.player(), new Net.GiveMark(r.code()));
            ctx.getSource().sendSuccess(() -> msg("region_given", "Region \"%s\" sent to the Atlas of %s", name, to.player().getGameProfile().getName()), true);
            return 1;
        }
        if (!WorldMarks.REGIONS.containsKey(id) && WorldMarks.REGIONS.size() >= WorldMarks.MAX_REGIONS) {
            throw fail("many", "Too many (at most %s)", WorldMarks.MAX_REGIONS);
        }
        boolean was = WorldMarks.REGIONS.containsKey(id);
        WorldMarks.REGIONS.put(id, r);
        WorldMarks.changed(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> was ? msg("region_changed", "Server region \"%s\" changed", id)
                : to.free() ? msg("region_added_free", "Region \"%s\" added to every Atlas: players may change or delete it", id)
                : msg("region_added", "Server region \"%s\" added: every Atlas shows it", id), true);
        return 1;
    }

    private static int markerAdd(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String id = id(ctx), name = name(ctx);
        int color = color(ctx), icon = IntegerArgumentType.getInteger(ctx, "icon");
        Target to = target(ctx);
        BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
        String dim = ctx.getSource().getLevel().dimension().location().toString();
        int y = Math.max(-2048, Math.min(4095, pos.getY()));
        WorldMarks.SMarker m = new WorldMarks.SMarker(id, name, dim, pos.getX(), y, pos.getZ(), icon, color, false, to.free());
        if (to.player() != null) { // into one player's Atlas, as their own marker
            PacketDistributor.sendToPlayer(to.player(), new Net.GiveMark(m.code()));
            ctx.getSource().sendSuccess(() -> msg("marker_given", "Marker \"%s\" sent to the Atlas of %s", name, to.player().getGameProfile().getName()), true);
            return 1;
        }
        if (!WorldMarks.MARKERS.containsKey(id) && WorldMarks.MARKERS.size() >= WorldMarks.MAX_MARKERS) {
            throw fail("many", "Too many (at most %s)", WorldMarks.MAX_MARKERS);
        }
        boolean was = WorldMarks.MARKERS.containsKey(id);
        WorldMarks.MARKERS.put(id, m);
        WorldMarks.changed(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> was ? msg("marker_changed", "Server marker \"%s\" changed", id)
                : to.free() ? msg("marker_added_free", "Marker \"%s\" added to every Atlas: players may change or delete it", id)
                : msg("marker_added", "Server marker \"%s\" added: every Atlas shows it", id), true);
        return 1;
    }

    private static int lead(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        Collection<ServerPlayer> players = EntityArgument.getPlayers(ctx, "players");
        BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
        String dim = ctx.getSource().getLevel().dimension().location().toString();
        for (ServerPlayer p : players) PacketDistributor.sendToPlayer(p, new Net.Lead(name, dim, pos.getX(), pos.getY(), pos.getZ(), false));
        ctx.getSource().sendSuccess(() -> msg("lead_set", "Leading %s player(s) to %s %s %s", players.size(), pos.getX(), pos.getY(), pos.getZ()), true);
        return players.size();
    }

    private static int reveal(CommandContext<CommandSourceStack> ctx, boolean forPlayers, boolean at, boolean generate) throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        if (level.dimensionType().hasCeiling()) throw fail("reveal_ceiling", "This does not work in worlds with a ceiling (like the Nether)");
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer p : forPlayers ? EntityArgument.getPlayers(ctx, "players") : List.of(ctx.getSource().getPlayerOrException())) {
            if (p.serverLevel() == level) players.add(p); // their game draws the map, and only for the world they are in
        }
        if (players.isEmpty()) throw fail("reveal_elsewhere", "The players must be in the world that is revealed");
        net.minecraft.world.phys.Vec3 here = ctx.getSource().getPosition();
        ColumnPos c = at ? ColumnPosArgument.getColumnPos(ctx, "center") : new ColumnPos((int) Math.floor(here.x), (int) Math.floor(here.z));
        int radius = IntegerArgumentType.getInteger(ctx, "radius");
        int chunks = Reveal.start(ctx.getSource(), level, c.x(), c.z(), radius, players, generate);
        ctx.getSource().sendSuccess(() -> generate
                ? msg("reveal_started_generate", "Revealing the map around %s %s: %s chunks; chunks that do not exist yet are generated", c.x(), c.z(), chunks)
                : msg("reveal_started", "Revealing the map around %s %s: %s chunks. Chunks that were never generated stay empty; add generate to make them", c.x(), c.z(), chunks), true);
        return chunks;
    }

    private AtlasCommands() {}
}

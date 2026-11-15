package com.wayfarersatlas;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Team markers and regions (FTB Teams): what a player marks "for the team" is seen at once by everyone in their
 * party team, in any Atlas, and anyone of the team may change or delete it. Kept in the world:
 * wayfarers_atlas/team_marks.txt. Also sends every player where their teammates are, wherever they are.
 */
public final class TeamStore {
    public static final int MAX = 500; // per team
    private static final Map<String, LinkedHashMap<String, String>> DATA = new HashMap<>(); // team -> uid -> code
    private static final Map<UUID, String> LAST = new HashMap<>(); // team id each player was last told about
    private static Path file;
    private static int tick = 0;

    public static void start(MinecraftServer server) {
        DATA.clear();
        LAST.clear();
        file = server.getWorldPath(LevelResource.ROOT).resolve("wayfarers_atlas").resolve("team_marks.txt").normalize();
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] p = line.split("\\|", 3); // team|uid|code
                if (p.length == 3 && valid(p[1], p[2])) DATA.computeIfAbsent(p[0], k -> new LinkedHashMap<>()).put(p[1], p[2]);
            }
        } catch (IOException e) {
            LogUtils.getLogger().warn("Wayfarer's Atlas: could not read {}", file, e);
        }
    }

    public static void stop() {
        DATA.clear();
        LAST.clear();
        file = null;
    }

    private static void write() {
        if (file == null) return;
        List<String> lines = new ArrayList<>();
        for (var t : DATA.entrySet()) for (var e : t.getValue().entrySet()) lines.add(t.getKey() + "|" + e.getKey() + "|" + e.getValue());
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LogUtils.getLogger().warn("Wayfarer's Atlas: could not save {}", file, e);
        }
    }

    private static boolean valid(String uid, String code) {
        // only the shape is checked here (the server has no use for what is in them); the games read them
        return uid.matches("[a-z0-9]{1,16}") && code.indexOf('\n') < 0 && code.indexOf('\r') < 0
                && ((code.startsWith("atlas|") && code.length() <= 200 && code.split("\\|", -1).length == 8)
                || (code.startsWith("region|") && code.length() <= 2000 && code.split("\\|", -1).length == 5));
    }

    public static void logout(ServerPlayer p) {
        LAST.remove(p.getUUID());
    }

    /** Every server tick: a player who joined or left a team learns it; twice a second: where the teammates are. */
    public static void tick(MinecraftServer server) {
        if (file == null || !TeamsCompat.present()) return;
        tick++;
        if (tick % 20 == 0) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                TeamsCompat.TeamInfo t = TeamsCompat.team(p);
                String id = t == null ? "" : t.id();
                if (!id.equals(LAST.get(p.getUUID()))) {
                    LAST.put(p.getUUID(), id);
                    send(p, t);
                }
            }
        }
        if (tick % 10 == 5) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                TeamsCompat.TeamInfo t = TeamsCompat.team(p);
                if (t == null) continue;
                List<Net.Mate> mates = new ArrayList<>();
                for (ServerPlayer o : t.online()) {
                    if (o == p || o.isSpectator()) continue;
                    mates.add(new Net.Mate(o.getUUID(), o.getGameProfile().getName(), o.level().dimension().location().toString(),
                            o.getX(), o.getZ(), o.getYRot()));
                }
                PacketDistributor.sendToPlayer(p, new Net.TeamMates(mates));
            }
        }
    }

    private static void send(ServerPlayer p, TeamsCompat.TeamInfo t) {
        List<String> codes = new ArrayList<>();
        if (t != null) {
            var map = DATA.get(t.id());
            if (map != null) for (var e : map.entrySet()) codes.add(e.getKey() + "|" + e.getValue());
        }
        PacketDistributor.sendToPlayer(p, new Net.TeamState(t == null ? "" : t.name(), codes));
    }

    /** A player adds (or changes) / removes a team marker or region. */
    public static void edit(ServerPlayer p, Net.TeamEdit e) {
        TeamsCompat.TeamInfo t = TeamsCompat.team(p);
        if (t == null || file == null) return;
        var map = DATA.computeIfAbsent(t.id(), k -> new LinkedHashMap<>());
        if (e.remove()) {
            if (map.remove(e.uid()) == null) return;
        } else {
            if (!valid(e.uid(), e.code())) return;
            if (!map.containsKey(e.uid()) && map.size() >= MAX) return;
            map.put(e.uid(), e.code());
        }
        if (map.isEmpty()) DATA.remove(t.id());
        write();
        for (ServerPlayer m : t.online()) send(m, t);
    }

    private TeamStore() {}
}

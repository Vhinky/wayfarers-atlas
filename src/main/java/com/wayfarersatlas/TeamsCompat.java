package com.wayfarersatlas;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * FTB Teams support (only when FTB Teams is installed, no hard dependency): which party team a player is in.
 * A player alone (FTB Teams gives everyone a "player team") has no team here.
 */
public final class TeamsCompat {
    /** A party team: its id, its name, who of it is online. */
    public record TeamInfo(String id, String name, List<ServerPlayer> online) {}

    private static boolean ok = false;
    private static Method api, isLoaded, getManager, teamFor, getId, getName, isParty, onlineMembers;

    public static void init() {
        if (!ModList.get().isLoaded("ftbteams")) return;
        try {
            Class<?> main = Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI");
            Class<?> apiI = Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI$API");
            Class<?> manager = Class.forName("dev.ftb.mods.ftbteams.api.TeamManager");
            Class<?> team = Class.forName("dev.ftb.mods.ftbteams.api.Team");
            api = main.getMethod("api");
            isLoaded = apiI.getMethod("isManagerLoaded");
            getManager = apiI.getMethod("getManager");
            teamFor = manager.getMethod("getTeamForPlayer", ServerPlayer.class);
            getId = team.getMethod("getId");
            getName = team.getMethod("getName");
            isParty = team.getMethod("isPartyTeam");
            onlineMembers = team.getMethod("getOnlineMembers");
            ok = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LogUtils.getLogger().warn("Wayfarer's Atlas: FTB Teams found, but its teams could not be read", e);
        }
    }

    public static boolean present() {
        return ok;
    }

    /** The party team of this player, or null (no FTB Teams, alone, or not loaded yet). */
    public static TeamInfo team(ServerPlayer p) {
        if (!ok) return null;
        try {
            Object a = api.invoke(null);
            if (a == null || !Boolean.TRUE.equals(isLoaded.invoke(a))) return null;
            Object t = ((Optional<?>) teamFor.invoke(getManager.invoke(a), p)).orElse(null);
            if (t == null || !Boolean.TRUE.equals(isParty.invoke(t))) return null;
            Object n = getName.invoke(t);
            String name = n instanceof Component c ? c.getString() : String.valueOf(n);
            List<ServerPlayer> online = new ArrayList<>();
            for (Object o : (Collection<?>) onlineMembers.invoke(t)) if (o instanceof ServerPlayer sp) online.add(sp);
            return new TeamInfo(String.valueOf(getId.invoke(t)), name.length() > 48 ? name.substring(0, 48) : name, online);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private TeamsCompat() {}
}

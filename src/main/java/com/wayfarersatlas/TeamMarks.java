package com.wayfarersatlas;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.PlayerSkin;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;

/**
 * Client side of team markers and regions (FTB Teams): ticked "For the team" in the marker / region dialog, it is
 * kept by the server for the team instead of in your Atlas, and everyone in the team sees it at once. Hiding one
 * is only for you (remembered by your Atlas). Also: where your teammates are, shown on the maps always.
 */
public final class TeamMarks {
    private static String team = null; // name of your party team, null = none
    public static final List<Marker> MARKERS = new ArrayList<>();
    public static final List<MarkerStore.Region> REGIONS = new ArrayList<>();

    /** A teammate somewhere (they may be far away, out of sight). */
    public record Mate(UUID id, String name, String dim, double x, double z, float yaw, PlayerSkin skin) {}

    private static List<Mate> mates = List.of();
    private static long matesAt = 0;

    public static boolean inTeam() {
        return team != null;
    }

    public static String team() {
        return team;
    }

    public static void reset() {
        team = null;
        MARKERS.clear();
        REGIONS.clear();
        mates = List.of();
    }

    public static void onState(Net.TeamState p) {
        Marker led = Nav.target();
        String ledUid = led != null ? led.teamUid : null;
        team = p.team().isEmpty() ? null : p.team();
        MARKERS.clear();
        REGIONS.clear();
        for (String s : p.codes()) {
            int bar = s.indexOf('|');
            if (bar <= 0) continue;
            String uid = s.substring(0, bar), code = s.substring(bar + 1);
            if (code.startsWith("region|")) {
                MarkerStore.Region r = MarkerStore.Region.decode(code);
                if (r != null) {
                    r.teamUid = uid;
                    REGIONS.add(r);
                }
            } else {
                Matcher m = ShareUtil.find(code);
                Marker mk = m == null ? null : ShareUtil.fromMatcher(m);
                if (mk != null) {
                    mk.teamUid = uid;
                    MARKERS.add(mk);
                }
            }
        }
        ServerMarks.applyHidden();
        if (ledUid != null) { // you were led to a team marker: keep following it (it is a new object now)
            Marker same = null;
            for (Marker m : MARKERS) if (ledUid.equals(m.teamUid) && !m.hidden) same = m;
            if (same != null) Nav.swap(same);
            else Nav.clear();
        }
    }

    public static void onMates(Net.TeamMates p) {
        Minecraft mc = Minecraft.getInstance();
        List<Mate> list = new ArrayList<>();
        for (Net.Mate m : p.mates()) {
            PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(m.id());
            if (info == null) continue;
            list.add(new Mate(m.id(), m.name(), m.dim(), m.x(), m.z(), m.yaw(), info.getSkin()));
        }
        mates = list;
        matesAt = Util.getMillis();
    }

    /** Teammates in that world (those the game shows as players anyway are drawn as such). */
    public static List<Mate> mates(String dim) {
        if (Util.getMillis() - matesAt > 3000) return List.of(); // nothing heard for a while: left the team / server
        List<Mate> out = new ArrayList<>();
        for (Mate m : mates) if (m.dim().equals(dim)) out.add(m);
        return out;
    }

    /** Is this teammate close enough to be a player the game itself shows? */
    public static boolean seen(Mate m) {
        var level = Minecraft.getInstance().level;
        return level != null && level.getPlayerByUUID(m.id()) != null;
    }

    // ------------------------------------------------------------------ changes

    private static String newUid() {
        return Long.toString(ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE, 36);
    }

    private static void send(String uid, String code, boolean remove) {
        PacketDistributor.sendToServer(new Net.TeamEdit(uid, code, remove));
    }

    /**
     * A marker was saved in the dialog. forTeam = the "For the team" box. Moves it between your Atlas and the
     * team as needed. isNew = it is not in any list yet.
     */
    public static void saved(Marker m, boolean forTeam, boolean isNew) {
        if (forTeam && inTeam()) {
            if (m.teamUid == null) {
                MarkerStore.MARKERS.remove(m);
                MarkerStore.save();
                m.teamUid = newUid();
                MARKERS.add(m); // shown at once; the server sends the list again
            }
            m.hidden = false;
            send(m.teamUid, ShareUtil.encode(m), false);
            return;
        }
        if (m.teamUid != null) { // no longer for the team: it goes into your Atlas
            send(m.teamUid, "", true);
            MARKERS.remove(m);
            m.teamUid = null;
            MarkerStore.MARKERS.add(m);
        } else if (isNew) {
            MarkerStore.MARKERS.add(m);
        }
        MarkerStore.save();
    }

    /** A region was saved in the dialog (see saved(Marker...)). */
    public static void saved(MarkerStore.Region r, boolean forTeam, boolean isNew) {
        if (forTeam && inTeam()) {
            if (r.teamUid == null) {
                MarkerStore.REGIONS.remove(r);
                MarkerStore.save();
                r.teamUid = newUid();
                REGIONS.add(r);
            }
            send(r.teamUid, r.encode(), false);
            return;
        }
        if (r.teamUid != null) {
            send(r.teamUid, "", true);
            REGIONS.remove(r);
            r.teamUid = null;
            MarkerStore.REGIONS.add(r);
        } else if (isNew) {
            MarkerStore.REGIONS.add(r);
        }
        MarkerStore.save();
    }

    /** Deletes a marker or a region: from your Atlas, or from the team. */
    public static void delete(Object o) {
        if (o instanceof Marker m) {
            if (Nav.is(m)) Nav.clear();
            if (m.teamUid != null) {
                send(m.teamUid, "", true);
                MARKERS.remove(m);
                return;
            }
            MarkerStore.MARKERS.remove(m);
        } else if (o instanceof MarkerStore.Region r) {
            if (r.teamUid != null) {
                send(r.teamUid, "", true);
                REGIONS.remove(r);
                return;
            }
            MarkerStore.REGIONS.remove(r);
        }
        MarkerStore.save();
    }

    /** Hides a marker (or shows it again): a team marker only for you, remembered by your Atlas. */
    public static void setHidden(Marker m, boolean hidden) {
        if (hidden && Nav.is(m)) Nav.clear();
        if (m.teamUid != null) {
            if (hidden) ServerMarks.HIDDEN.add("t:" + m.teamUid);
            else ServerMarks.HIDDEN.remove("t:" + m.teamUid);
            ServerMarks.applyHidden();
        } else {
            m.hidden = hidden;
        }
        MarkerStore.save();
    }

    private TeamMarks() {}
}

package com.wayfarersatlas;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Client side of the server's markers and regions (/atlas marker, /atlas region): every Atlas shows them. They are
 * not edited or deleted, only hidden - and what is hidden is remembered by the Atlas you carry, like its own markers.
 * Also: things an operator sends into your Atlas, and "/atlas lead".
 */
public final class ServerMarks {
    /** Id of the place "/atlas lead" leads to (it is not kept anywhere). */
    public static final String LEAD = "#lead";

    public static final List<Marker> MARKERS = new ArrayList<>();
    public static final List<MarkerStore.Region> REGIONS = new ArrayList<>();
    /**
     * Kept by the Atlas that is shown: "m:id" / "r:id" = hidden, "g:m:id" / "g:r:id" = a removable one it already got
     * a copy of (so a deleted copy does not come back).
     */
    public static final Set<String> HIDDEN = new LinkedHashSet<>();
    /** Removable ones of the server ("M|id|code" / "R|id|code"): every Atlas gets its own copy once. */
    private static final List<String[]> REMOVABLE = new ArrayList<>();

    /** The server's list (again after every change): "m|id|<marker code>", "r|id|<region code>". */
    public static void onList(List<String> codes) {
        Marker led = Nav.target();
        String ledId = led != null && led.serverId != null && !led.serverId.equals(LEAD) ? led.serverId : null;
        MARKERS.clear();
        REGIONS.clear();
        REMOVABLE.clear();
        for (String s : codes) {
            String[] p = s.split("\\|", 3);
            if (p.length < 3) continue;
            if (p[0].equals("M") || p[0].equals("R")) {
                REMOVABLE.add(p);
                continue;
            }
            if (p[0].equals("m")) {
                Matcher m = ShareUtil.find(p[2]);
                Marker mk = m == null ? null : ShareUtil.fromMatcher(m);
                if (mk != null) {
                    mk.serverId = p[1];
                    MARKERS.add(mk);
                }
            } else if (p[0].equals("r")) {
                MarkerStore.Region r = MarkerStore.Region.decode(p[2]);
                if (r != null) {
                    r.serverId = p[1];
                    REGIONS.add(r);
                }
            }
        }
        applyHidden();
        giveRemovable();
        if (ledId != null) { // you were being led to a server marker: keep following it (it is a new object now)
            Marker same = null;
            for (Marker m : MARKERS) if (m.serverId.equals(ledId) && !m.hidden) same = m;
            if (same != null) Nav.swap(same);
            else Nav.clear();
        }
    }

    /** Marks what the Atlas that is shown has hidden. */
    public static void applyHidden() {
        for (Marker m : MARKERS) m.hidden = HIDDEN.contains("m:" + m.serverId);
        for (MarkerStore.Region r : REGIONS) r.hidden = HIDDEN.contains("r:" + r.serverId);
        for (Marker m : TeamMarks.MARKERS) m.hidden = HIDDEN.contains("t:" + m.teamUid); // team markers hidden only for you
    }

    /** Hides a server marker / region in this Atlas, or shows it again. */
    public static void setHidden(Object o, boolean hidden) {
        String key = o instanceof Marker m ? "m:" + m.serverId : "r:" + ((MarkerStore.Region) o).serverId;
        if (hidden) HIDDEN.add(key);
        else HIDDEN.remove(key);
        applyHidden();
        if (hidden && o instanceof Marker m && Nav.is(m)) Nav.clear();
        MarkerStore.save();
    }

    public static void reset() {
        MARKERS.clear();
        REGIONS.clear();
        HIDDEN.clear();
        REMOVABLE.clear();
    }

    /** Copies of the removable server markers and regions go into the Atlas that is shown (each once). */
    public static void giveRemovable() {
        if (REMOVABLE.isEmpty() || AtlasSync.active() == null || !AtlasSync.markersLoaded()) return;
        boolean changed = false;
        for (String[] p : REMOVABLE) {
            boolean region = p[0].equals("R");
            String key = (region ? "g:r:" : "g:m:") + p[1];
            if (HIDDEN.contains(key)) continue;
            if (region) {
                MarkerStore.Region r = MarkerStore.Region.decode(p[2]);
                if (r == null) continue;
                boolean dup = false;
                for (MarkerStore.Region e : MarkerStore.REGIONS) dup |= e.encode().equals(r.encode());
                if (!dup) MarkerStore.REGIONS.add(r);
            } else {
                Matcher m = ShareUtil.find(p[2]);
                Marker mk = m == null ? null : ShareUtil.fromMatcher(m);
                if (mk == null) continue;
                boolean dup = false;
                for (Marker e : MarkerStore.MARKERS) {
                    dup |= e.dim.equals(mk.dim) && e.x == mk.x && e.y == mk.y && e.z == mk.z && e.name.equals(mk.name);
                }
                if (!dup) MarkerStore.MARKERS.add(mk);
            }
            HIDDEN.add(key);
            changed = true;
        }
        if (changed) MarkerStore.save();
    }

    /** An operator sent a marker or a region into the Atlas you carry: it becomes your own. */
    public static void onGive(String code) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (AtlasSync.active() == null || !ClientEvents.hasAtlas()) {
            mc.player.displayClientMessage(Component.translatable("msg.wayfarers_atlas.need_atlas"), true);
            return;
        }
        String name;
        if (code.startsWith("region|")) {
            MarkerStore.Region r = MarkerStore.Region.decode(code);
            if (r == null) return;
            boolean dup = false;
            for (MarkerStore.Region e : MarkerStore.REGIONS) dup |= e.encode().equals(r.encode());
            if (!dup) {
                MarkerStore.REGIONS.add(r);
                MarkerStore.save();
            }
            name = r.name;
        } else {
            Marker m = ShareUtil.importCode(code); // adds it (once) and saves
            if (m == null) return;
            name = m.name;
        }
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.5f, 0.7f));
        mc.player.displayClientMessage(Component.translatable("msg.wayfarers_atlas.given", name), true);
    }

    /** "/atlas lead": follow this place (it is shown as a marker while you follow it), or stop. */
    public static void onLead(Net.Lead p) {
        if (p.clear()) {
            Marker t = Nav.target();
            if (t != null && LEAD.equals(t.serverId)) Nav.clear();
            return;
        }
        String name = p.name().isBlank() ? I18n.get("marker.wayfarers_atlas.goal") : p.name();
        Marker m = new Marker(name, p.dim(), p.x(), p.y(), p.z(), 0xFFD86A, 0);
        m.serverId = LEAD;
        Nav.set(m);
    }

    private ServerMarks() {}
}

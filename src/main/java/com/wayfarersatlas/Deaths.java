package com.wayfarersatlas;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.List;

/**
 * Death page: when you die, the Atlas that is shown gets a marker (skull) at that spot - a normal marker you can
 * teleport to, share or delete. The last three are kept; one disappears when you get back to it.
 */
public final class Deaths {
    public static final int ICON = 58; // skull
    private static boolean wasDead = false;

    /** The page is in the Atlas that is shown and the server allows it. */
    public static boolean active() {
        return Upgrades.DEATH.in(Math.max(0, ClientEvents.atlasUpgrades())) && ServerRules.deathPage();
    }

    private static List<Marker> marks() {
        List<Marker> out = new ArrayList<>();
        for (Marker m : MarkerStore.MARKERS) if (m.death) out.add(m);
        return out;
    }

    public static void tick(Minecraft mc) {
        boolean dead = mc.player.isDeadOrDying();
        if (dead && !wasDead && active()) {
            Marker m = new Marker(I18n.get("marker.wayfarers_atlas.death"), MapScanner.dimId(mc.level),
                    mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ(), 0xF2EBDD, ICON);
            m.death = true;
            MarkerStore.MARKERS.add(m);
            List<Marker> all = marks();
            for (int i = 0; i < all.size() - 3; i++) MarkerStore.MARKERS.remove(all.get(i)); // keep the last three
            MarkerStore.save();
        }
        wasDead = dead;
        if (!dead) { // back at the spot: the mark is no longer needed
            String dim = MapScanner.dimId(mc.level);
            if (MarkerStore.MARKERS.removeIf(m -> m.death && m.dim.equals(dim) && Math.abs(m.x + 0.5 - mc.player.getX()) < 3
                    && Math.abs(m.z + 0.5 - mc.player.getZ()) < 3 && Math.abs(m.y - mc.player.getY()) < 6)) {
                MarkerStore.save();
            }
        }
    }

    public static void reset() {
        wasDead = false;
    }

    private Deaths() {}
}

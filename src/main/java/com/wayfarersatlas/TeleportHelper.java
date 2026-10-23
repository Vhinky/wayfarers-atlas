package com.wayfarersatlas;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Locale;

/**
 * Teleports to a safe spot on the ground (never into a cave or onto the Nether roof), even when the target area
 * is not loaded yet. Only the final teleport is reported in chat.
 */
public final class TeleportHelper {
    private static boolean pending = false;
    private static int px, pz, ticks;
    private static double fromX, fromY, fromZ; // where the player was before an unknown-area teleport
    private static long suppressUntil = 0;
    private static int hideTeleports = 0; // how many of our own "Teleported ..." messages to hide

    /** Y to stand on, or Marker.NO_Y if the ground there is unknown. */
    public static int surfaceY(ClientLevel level, int bx, int bz) {
        if (level.getChunkSource().getChunkNow(bx >> 4, bz >> 4) != null) {
            int y = groundY(level, bx, bz);
            if (y != Marker.NO_Y) return y;
        }
        if (level.dimensionType().hasCeiling()) return Marker.NO_Y; // Nether: the map shows caves, not a surface
        int cached = MapCache.heightAt(MapScanner.dimId(level), bx, bz);
        return cached == Integer.MIN_VALUE ? Marker.NO_Y : cached + 1;
    }

    /**
     * Ground in a loaded chunk. Open sky: the top block. Under a ceiling (Nether): the highest spot below the roof
     * with solid ground and two free blocks above, not over lava.
     */
    private static int groundY(ClientLevel level, int bx, int bz) {
        if (!level.dimensionType().hasCeiling()) {
            int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
            return h > level.getMinBuildHeight() ? h : Marker.NO_Y;
        }
        int top = Math.min(level.getMaxBuildHeight() - 3, level.getMinBuildHeight() + level.dimensionType().logicalHeight() - 3);
        top = Math.min(top, 123); // below the bedrock roof
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = top; y > level.getMinBuildHeight() + 1; y--) {
            BlockState floor = level.getBlockState(p.set(bx, y - 1, bz));
            if (!floor.blocksMotion() || !floor.getFluidState().isEmpty()) continue;
            BlockState feet = level.getBlockState(p.set(bx, y, bz));
            BlockState head = level.getBlockState(p.set(bx, y + 1, bz));
            if (!feet.blocksMotion() && !head.blocksMotion() && feet.getFluidState().isEmpty() && head.getFluidState().isEmpty()) {
                return y;
            }
        }
        return Marker.NO_Y;
    }

    /**
     * Nearest spot with ground around (bx, bz): first in loaded chunks, then in the map's memory.
     * Returns {x, y, z} or null if there is nothing within ~96 blocks.
     */
    private static int[] nearestGround(ClientLevel level, int bx, int bz) {
        final int R = 96;
        for (int r = 1; r <= R; r++) {
            int step = r <= 16 ? 1 : 2;
            int[] best = null;
            long bestD = Long.MAX_VALUE;
            for (int dx = -r; dx <= r; dx += step) {
                for (int dz = -r; dz <= r; dz += step) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) < r - step + 1) continue; // only the ring
                    int x = bx + dx, z = bz + dz;
                    int y = Marker.NO_Y;
                    if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) != null) {
                        y = groundY(level, x, z);
                    } else if (!level.dimensionType().hasCeiling()) {
                        int h = MapCache.heightAt(MapScanner.dimId(level), x, z);
                        if (h != Integer.MIN_VALUE) y = h + 1;
                    }
                    if (y == Marker.NO_Y) continue;
                    long d = (long) dx * dx + (long) dz * dz;
                    if (d < bestD) {
                        bestD = d;
                        best = new int[]{x, y, z};
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    /** True for chat feedback of our own helper commands (slow falling, the temporary jump up) - those are hidden. */
    public static boolean isHiddenFeedback(Component c) {
        if (System.currentTimeMillis() > suppressUntil) return false;
        if (!(c.getContents() instanceof TranslatableContents tc)) return false;
        String k = tc.getKey();
        if (k.startsWith("commands.effect.")) return true;
        if (k.startsWith("commands.teleport.") && hideTeleports > 0) {
            hideTeleports--;
            return true;
        }
        return false;
    }

    private static void command(Minecraft mc, String cmd) {
        suppressUntil = System.currentTimeMillis() + 8000;
        mc.player.connection.sendCommand(cmd);
    }

    /**
     * Y to stand on for a point of the map: in cave view the cave floor, otherwise the surface.
     * Marker.NO_Y if nothing is known about that spot.
     */
    public static int pointY(ClientLevel level, int bx, int bz) {
        if (MapScanner.isCave()) {
            int h = MapCache.heightAt(MapScanner.viewKey(level), bx, bz);
            if (h != Integer.MIN_VALUE) return h + 1;
        }
        return surfaceY(level, bx, bz);
    }

    /** by = Marker.NO_Y means "find the ground". */
    public static void go(int bx, int by, int bz) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        int y = by == Marker.NO_Y ? pointY(mc.level, bx, bz) : by;
        if (y != Marker.NO_Y) {
            tp(mc, bx, y, bz);
            return;
        }
        if (mc.level.getChunkSource().getChunkNow(bx >> 4, bz >> 4) != null) {
            // the area is loaded and there is no ground (void): go to the nearest place that has ground
            int[] n = nearestGround(mc.level, bx, bz);
            if (n != null) {
                tp(mc, n[0], n[1], n[2]);
                mc.player.displayClientMessage(Component.translatable("msg.wayfarers_atlas.tp_nearest"), true);
            } else {
                mc.player.displayClientMessage(Component.translatable("msg.wayfarers_atlas.tp_void"), true);
            }
            return;
        }
        fromX = mc.player.getX();
        fromY = mc.player.getY();
        fromZ = mc.player.getZ();
        // unknown area: jump to the top of this world (this message is hidden), float down slowly,
        // and land on the ground as soon as the chunk arrives (that one is shown)
        int high = mc.level.getMaxBuildHeight() - 2;
        hideTeleports++;
        suppressUntil = System.currentTimeMillis() + 8000;
        tp(mc, bx, high, bz);
        command(mc, "effect give @s minecraft:slow_falling 90 0 true");
        pending = true;
        px = bx;
        pz = bz;
        ticks = 0;
    }

    private static void tp(Minecraft mc, int x, int y, int z) {
        mc.player.connection.sendCommand(String.format(Locale.ROOT, "tp @s %.1f %d %.1f", x + 0.5, y, z + 0.5));
    }

    public static void tick(Minecraft mc) {
        if (!pending) return;
        if (mc.player == null || mc.level == null || ++ticks > 20 * 45) {
            pending = false;
            return;
        }
        if (mc.level.getChunkSource().getChunkNow(px >> 4, pz >> 4) == null) return;
        int y = groundY(mc.level, px, pz);
        if (y == Marker.NO_Y) {
            if (ticks < 10) return; // give the chunk a moment to fill in
            int[] n = nearestGround(mc.level, px, pz); // void there: the nearest place with ground
            if (n != null) {
                tp(mc, n[0], n[1], n[2]);
                command(mc, "effect clear @s minecraft:slow_falling");
                mc.player.displayClientMessage(Component.translatable("msg.wayfarers_atlas.tp_nearest"), true);
                pending = false;
                return;
            }
            // nothing anywhere near: go back to where the player was
            hideTeleports++;
            suppressUntil = System.currentTimeMillis() + 8000;
            mc.player.connection.sendCommand(String.format(Locale.ROOT, "tp @s %.2f %.2f %.2f", fromX, fromY, fromZ));
            command(mc, "effect clear @s minecraft:slow_falling");
            mc.player.displayClientMessage(Component.translatable("msg.wayfarers_atlas.tp_void"), true);
            pending = false;
            return;
        }
        tp(mc, px, y, pz);
        command(mc, "effect clear @s minecraft:slow_falling");
        pending = false;
    }

    private TeleportHelper() {}
}

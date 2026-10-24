package com.wayfarersatlas;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.Npc;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Creature radar (Atlas upgrade). Shows mobs within the simulation distance - the area where mobs actually
 * move and spawn - as small coloured dots: red hostile, green animals, blue water creatures, yellow villagers,
 * pink tamed pets. Underground creatures are hidden in the surface view (see visibleInHeight).
 * While the player-list key (TAB) is held, every creature is shown as its head instead (heads may overlap,
 * nearer ones on top); a creature that cannot be drawn (some modded mobs) is shown by its name.
 */
public final class Radar {
    // bright but warm colours that still fit the parchment look
    public static final int HOSTILE = 0xFFFF4A36, ANIMAL = 0xFF8CEB4F, WATER = 0xFF47C6FF,
            VILLAGER = 0xFFFFD43B, PET = 0xFFFF8AD0;

    public record Blip(Mob e, double x, double z, double dist2, int color, float alpha) {}

    /** An item lying on the ground. */
    public record ItemBlip(net.minecraft.world.entity.item.ItemEntity e, double x, double z, double dist2, float alpha) {}

    public static final int ITEM = 0xFFF4EEDC; // items: pale, like a speck of paper

    /** Creatures further than this above or below you are not shown (same as Xaero's radar). */
    public static final int HEIGHT = 20;

    private static Field simField;
    private static boolean simFieldFailed = false;

    /** True while the radar is installed in the carried Atlas and switched on in the settings. */
    public static boolean active() {
        return Upgrades.RADAR.in(Math.max(0, ClientEvents.atlasUpgrades())) && AtlasConfig.RADAR.get() && ServerRules.radar();
    }

    /** True while the items page is in the Atlas that is shown, switched on, and allowed by the server. */
    public static boolean itemsActive() {
        return Upgrades.ITEMS.in(Math.max(0, ClientEvents.atlasUpgrades())) && AtlasConfig.RADAR_ITEMS.get() && ServerRules.itemsPage();
    }

    /** True while the player-list key (TAB by default) is held - also inside the map screen. */
    public static boolean portraitsHeld() {
        return Keys.physicallyDown(Minecraft.getInstance().options.keyPlayerList);
    }

    /** Simulation distance in chunks: the server's value if it can be read, otherwise the game option. */
    public static int simulationDistance(Minecraft mc) {
        ClientPacketListener c = mc.getConnection();
        if (c != null && !simFieldFailed) {
            try {
                if (simField == null) {
                    simField = ClientPacketListener.class.getDeclaredField("serverSimulationDistance");
                    simField.setAccessible(true);
                }
                int v = simField.getInt(c);
                if (v > 0) return v;
            } catch (ReflectiveOperationException | RuntimeException e) {
                simFieldFailed = true;
            }
        }
        return mc.options.simulationDistance().get();
    }

    public static int colorOf(Entity e) {
        if (e instanceof Enemy) return HOSTILE;
        if (e instanceof TamableAnimal t && t.isTame()) return PET;
        if (e instanceof Npc) return VILLAGER;
        if (e instanceof WaterAnimal) return WATER;
        return ANIMAL;
    }

    /** All creatures the radar shows right now (nearest first), with interpolated positions. */
    public static List<Blip> scan(Minecraft mc, float pt) {
        List<Blip> out = new ArrayList<>();
        if (mc.level == null || mc.player == null || !active()) return out;
        Vec3 me = mc.player.getPosition(pt);
        double r = simulationDistance(mc) * 16.0;
        double r2 = r * r;
        boolean passive = AtlasConfig.RADAR_PASSIVE.get();
        boolean cave = MapScanner.isCave();
        int range = HEIGHT;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof Mob mob) || !e.isAlive() || e.isInvisible()) continue;
            int color = colorOf(e);
            if (!passive && color != HOSTILE) continue;
            Vec3 p = e.getPosition(pt);
            double dx = p.x - me.x, dz = p.z - me.z;
            double d2 = dx * dx + dz * dz;
            if (d2 > r2) continue;
            if (!visibleInHeight(mc, e, p, me, cave, range)) continue;
            float dyAbs = (float) Math.abs(p.y - me.y);
            float shade = Mth.clamp(1f - (dyAbs - 4f) / Math.max(1f, range - 4f) * 0.55f, 0.45f, 1f); // darker further up / down
            out.add(new Blip(mob, p.x, p.z, d2, color, shade));
        }
        out.sort(Comparator.comparingDouble(Blip::dist2));
        return out;
    }

    /** Items lying around (same range and height rules as creatures), nearest first, at most 256. */
    public static List<ItemBlip> scanItems(Minecraft mc, float pt) {
        List<ItemBlip> out = new ArrayList<>();
        if (mc.level == null || mc.player == null || !itemsActive()) return out;
        Vec3 me = mc.player.getPosition(pt);
        double r = simulationDistance(mc) * 16.0;
        double r2 = r * r;
        boolean cave = MapScanner.isCave();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof net.minecraft.world.entity.item.ItemEntity it) || !e.isAlive() || it.getItem().isEmpty()) continue;
            Vec3 p = e.getPosition(pt);
            double dx = p.x - me.x, dz = p.z - me.z;
            double d2 = dx * dx + dz * dz;
            if (d2 > r2 || !visibleInHeight(mc, e, p, me, cave, HEIGHT)) continue;
            float dyAbs = (float) Math.abs(p.y - me.y);
            float shade = Mth.clamp(1f - (dyAbs - 4f) / Math.max(1f, HEIGHT - 4f) * 0.55f, 0.45f, 1f);
            out.add(new ItemBlip(it, p.x, p.z, d2, shade));
        }
        out.sort(Comparator.comparingDouble(ItemBlip::dist2));
        return out.size() > 256 ? new ArrayList<>(out.subList(0, 256)) : out;
    }

    /** An item: a small pale dot, or (TAB held) the item itself, small. */
    public static void item(GuiGraphics g, ItemBlip b, double sx, double sy, boolean icon, int dotPx) {
        if (!icon) {
            dot(g, sx, sy, dotPx, ITEM, b.alpha());
            return;
        }
        g.pose().pushPose();
        g.pose().translate((float) sx, (float) sy, 0f);
        g.pose().scale(0.6f, 0.6f, 1f);
        g.renderItem(b.e().getItem(), -8, -8);
        g.pose().popPose();
    }

    private static final java.util.Map<Integer, Boolean> UNDER = new java.util.HashMap<>();
    private static long underTick = -1;

    /**
     * Height filter, like Xaero's radar: nothing further than the radar height (20 blocks by default) above or
     * below you. In the surface view, creatures inside the ground (caves) are also hidden unless they are on your
     * own level (+-3 blocks); water, leaves and logs above a creature do not count as ground.
     */
    private static boolean visibleInHeight(Minecraft mc, Entity e, Vec3 p, Vec3 me, boolean cave, int range) {
        double dy = p.y - me.y;
        if (Math.abs(dy) > range) return false; // like Xaero: nothing beyond the radar height
        if (cave) return true;
        if (Math.abs(dy) <= 3) return true;
        return !underground(mc, e);
    }

    /** True if solid ground (not water, not leaves) is above the creature. Checked once per game tick. */
    private static boolean underground(Minecraft mc, Entity e) {
        long t = mc.level.getGameTime();
        if (t != underTick) {
            UNDER.clear();
            underTick = t;
        }
        return UNDER.computeIfAbsent(e.getId(), id -> {
            int bx = e.getBlockX(), bz = e.getBlockZ();
            int top = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            for (int y = Mth.floor(e.getEyeY()) + 1; y < top; y++) {
                BlockState st = mc.level.getBlockState(m.set(bx, y, bz));
                if (st.isAir() || !st.getFluidState().isEmpty() || st.is(BlockTags.LEAVES) || st.is(BlockTags.LOGS)) continue;
                if (st.blocksMotion() && st.isSolidRender(mc.level, m)) return true;
            }
            return false;
        });
    }

    /** A small round dot in a bright colour with a thin dark rim; 'shade' darkens it for creatures above / below. */
    public static void dot(GuiGraphics g, double sx, double sy, int corePx, int color, float shade) {
        double sc = Minecraft.getInstance().getWindow().getGuiScale();
        int col = 0xFF000000 | Ui.lerpColor(0xFF000000 | color, 0xFF000000, 1f - shade) & 0xFFFFFF;
        int shine = 0xFF000000 | Ui.lerpColor(col, 0xFFFFFFFF, 0.5f) & 0xFFFFFF;
        int rim = 0xE0140D06;
        g.pose().pushPose();
        g.pose().scale((float) (1 / sc), (float) (1 / sc), 1f);
        g.pose().translate(Math.round(sx * sc), Math.round(sy * sc), 0f); // on the pixel grid
        int lo = -corePx / 2, hi = lo + corePx;
        g.fill(lo - 1, lo, hi + 1, hi, rim);   // rounded dark rim
        g.fill(lo, lo - 1, hi, hi + 1, rim);
        g.fill(lo, lo, hi, hi, col);            // bright core
        if (corePx >= 4) g.fill(lo, lo, lo + 1, lo + 1, shine);
        g.pose().popPose();
    }

    /** The creature's flat face (pxPerTexel screen pixels per texture pixel); its name if it cannot be drawn. */
    public static void head(GuiGraphics g, LivingEntity e, double sx, double sy, int pxPerTexel, int color, float alpha) {
        if (MobHeads.draw(g, e, sx, sy, pxPerTexel, alpha)) return;
        nameTag(g, e, sx, sy, color, alpha);
    }

    /**
     * The creature's name: white text with a shadow, no background. Each font pixel is exactly 2x2 screen pixels and
     * the text sits on the pixel grid, so it stays sharp and does not shimmer while the creature moves.
     */
    public static void nameTag(GuiGraphics g, LivingEntity e, double sx, double sy, int color, float alpha) {
        var mc = Minecraft.getInstance();
        var font = mc.font;
        String name = e.getName().getString();
        double sc = mc.getWindow().getGuiScale();
        int fp = 2; // screen pixels per font pixel
        int w = font.width(name);
        float rx = Math.round(sx * sc - (w * fp) / 2.0), ry = Math.round(sy * sc - 4.0 * fp); // on the pixel grid
        g.pose().pushPose();
        g.pose().scale((float) (1 / sc), (float) (1 / sc), 1f);
        g.pose().translate(rx, ry, 0f);
        g.pose().scale(fp, fp, 1f);
        int c = (int) (255 * alpha);
        g.drawString(font, name, 0, 0, 0xFF000000 | (c << 16) | (c << 8) | c, true);
        g.pose().popPose();
    }

    private Radar() {}
}

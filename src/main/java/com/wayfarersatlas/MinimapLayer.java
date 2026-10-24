package com.wayfarersatlas;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import org.joml.Matrix4fStack;

public final class MinimapLayer implements LayeredDraw.Layer {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(WayfarersAtlas.MODID, "minimap");
    private static final double[] LEVELS = {4.0, 2.0, 1.0, 0.5}; // real screen pixels per block

    private static int levelIdx = 1;
    private static double ppb = 2.0;     // animated towards LEVELS[levelIdx]
    private static long lastNs = 0;
    private static float caveAlpha = 0f; // fades the "Cave" label in and out
    private static float fade = 0f;      // the whole minimap fades in when you pick up the Atlas, out when you drop it
    private final MapView view = new MapView(10);

    public static void register(RegisterGuiLayersEvent e) {
        e.registerAboveAll(ID, new MinimapLayer());
    }

    public static void cycleZoom() {
        levelIdx = (levelIdx + 1) % LEVELS.length;
    }

    /** Icons get a little smaller when zoomed out. */
    public static float iconScale(double ppb) {
        return (float) Mth.clamp(0.82 + 0.1 * Math.log(ppb) / Math.log(2), 0.6, 1.0);
    }

    static double glide(double cur, double target, double dt) {
        if (cur == target) return cur;
        double ln = Math.log(cur), lt = Math.log(target);
        double next = ln + (lt - ln) * (1.0 - Math.exp(-dt * 10.0));
        return Math.abs(lt - next) < 0.01 ? target : Math.exp(next);
    }

    /**
     * Some mods (zoom mods, HUD scalers) transform the whole HUD while they are active. The minimap ignores that
     * and is always drawn with a clean transform, so it stays exactly where it is - like Xaero's.
     */
    @Override
    public void render(GuiGraphics g, DeltaTracker dt) {
        g.flush(); // draw whatever was queued before us with the transform it was meant for
        Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.pushMatrix();
        mv.translation(0f, 0f, -11000f); // the plain HUD transform vanilla uses (keeps depth right)
        RenderSystem.applyModelViewMatrix();
        g.pose().pushPose();
        g.pose().last().pose().identity();
        g.pose().last().normal().identity();
        try {
            draw(g, dt);
            g.flush(); // our quads go out with the clean transform
        } finally {
            g.setColor(1f, 1f, 1f, 1f);
            Ui.alpha = 1f;
            g.pose().popPose();
            mv.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private void draw(GuiGraphics g, DeltaTracker dt) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        double dts = lastNs == 0 ? 0 : Math.min(0.1, (now - lastNs) / 1e9);
        lastNs = now;
        if (mc.player == null || mc.level == null) {
            fade = 0f;
            return;
        }
        // smooth fade: ~0.3 s in when the Atlas is picked up / put on, out when it is dropped or moved away
        float target = ClientEvents.hasAtlas() && AtlasConfig.MINIMAP.get() ? 1f : 0f;
        fade += (target - fade) * (float) Math.min(1.0, dts * 9.0);
        if (Math.abs(target - fade) < 0.005f) fade = target;
        if (fade <= 0.01f || mc.options.hideGui
                || mc.screen instanceof MapScreen || mc.screen instanceof MarkerScreen) return;
        float eased = fade * fade * (3f - 2f * fade); // smoothstep
        Ui.alpha = eased;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.setColor(1f, 1f, 1f, eased);
        ppb = glide(ppb, LEVELS[levelIdx], dts);
        caveAlpha += ((MapScanner.isCave() ? 1f : 0f) - caveAlpha) * (float) Math.min(1.0, dts * 5.0);

        float pt = dt.getGameTimeDeltaPartialTick(true);
        Vec3 raw = mc.player.getPosition(pt); // interpolated, so the map glides instead of jumping per tick
        // the world chosen on the big map: in another world you see where you would be there (Nether x8)
        String curDim = MapScanner.dimId(mc.level);
        String sel = MapScreen.selectedDim(curDim);
        boolean other = sel != null;
        String dim = other ? sel : curDim;
        String key = other ? MapScanner.keyFor(sel) : MapScanner.viewKey(mc.level);
        double k = other ? MapScreen.scaleOf(curDim) / MapScreen.scaleOf(sel) : 1.0;
        Vec3 pos = other ? new Vec3(raw.x * k, raw.y, raw.z * k) : raw;
        double sc = mc.getWindow().getGuiScale();
        double bpp = sc / ppb;
        float is = iconScale(ppb);

        int SIZE = AtlasConfig.MINIMAP_SIZE.get() | 1; // odd: the centre is a real pixel
        boolean showCoords = AtlasConfig.SHOW_COORDS.get(), showBiome = AtlasConfig.SHOW_BIOME.get();
        int textH = (showCoords ? 8 : 0) + (showBiome ? 8 : 0) + 4;
        int outer = SIZE + 10;
        AtlasConfig.Corner corner = AtlasConfig.MINIMAP_CORNER.get();
        boolean right = corner == AtlasConfig.Corner.TOP_RIGHT || corner == AtlasConfig.Corner.BOTTOM_RIGHT;
        boolean bottom = corner == AtlasConfig.Corner.BOTTOM_RIGHT || corner == AtlasConfig.Corner.BOTTOM_LEFT;
        int ox = right ? g.guiWidth() - outer - 6 : 6;
        int oy = bottom ? g.guiHeight() - outer - 6 - textH : 6;
        int x = ox + 5, y = oy + 5;

        boolean round = AtlasConfig.MINIMAP_ROUND.get();
        double midX = x + SIZE / 2.0, midY = y + SIZE / 2.0, R = SIZE / 2.0;
        if (round) Ui.roundFrame(g, midX, midY, R);
        else Ui.frame(g, ox, oy, outer, outer);

        // north up, or the map turns with you (what is in front of you is up)
        boolean northUp = AtlasConfig.NORTH_UP.get();
        double rot = northUp ? 0 : Math.toRadians(180.0 - mc.player.getViewYRot(pt));
        double cos = Math.cos(rot), sin = Math.sin(rot);
        Runnable drawMap = () -> {
            if (northUp) {
                view.render(g, x, y, SIZE, SIZE, pos.x, pos.z, bpp, key, mc.player.tickCount);
            } else { // a bigger square, turned: the corners stay filled
                int pad = (int) Math.ceil(SIZE * 0.215) + 2;
                g.pose().pushPose();
                g.pose().translate((float) midX, (float) midY, 0f);
                g.pose().mulPose(com.mojang.math.Axis.ZP.rotation((float) rot));
                g.pose().translate((float) -midX, (float) -midY, 0f);
                view.render(g, x - pad, y - pad, SIZE + 2 * pad, SIZE + 2 * pad, pos.x, pos.z, bpp, key, mc.player.tickCount);
                g.pose().popPose();
            }
        };
        if (round) { // the map cut to a circle, one row at a time
            for (int yy = y; yy < y + SIZE; yy++) {
                double dy = yy + 0.5 - midY;
                double h = Math.sqrt(Math.max(0, R * R - dy * dy));
                int x0 = (int) Math.round(midX - h), x1 = (int) Math.round(midX + h);
                if (x1 <= x0) continue;
                g.enableScissor(x0, yy, x1, yy + 1);
                drawMap.run();
                g.disableScissor();
            }
        }
        g.enableScissor(x, y, x + SIZE, y + SIZE);
        if (!round) drawMap.run();
        // centre, glued to the terrain
        double cX = northUp ? midX + view.shiftX : midX, cY = northUp ? midY + view.shiftY : midY;
        final double fbpp = bpp;
        Trail.Proj proj = (wx, wz) -> {
            double dx = (wx - pos.x) / fbpp, dz = (wz - pos.z) / fbpp;
            return new double[]{cX + dx * cos - dz * sin, cY + dx * sin + dz * cos};
        };

        // creatures (radar upgrade): small dots, or their heads while TAB is held (overlapping, nearer on top)
        boolean faces = Radar.portraitsHeld();
        if (!other) { // items on the ground, under the creatures
            java.util.List<Radar.ItemBlip> items = Radar.scanItems(mc, pt);
            for (int i = items.size() - 1; i >= 0; i--) {
                Radar.ItemBlip ib = items.get(i);
                double[] s = proj.at(ib.x(), ib.z());
                if (s[0] < x + 2 || s[0] > x + SIZE - 2 || s[1] < y + 2 || s[1] > y + SIZE - 2) continue;
                if (round && Math.hypot(s[0] - midX, s[1] - midY) > R - 2) continue;
                Radar.item(g, ib, s[0], s[1], faces, 3);
            }
        }
        java.util.List<Radar.Blip> blips = other ? java.util.List.of() : Radar.scan(mc, pt);
        boolean drewHead = false;
        for (int i = blips.size() - 1; i >= 0; i--) { // farthest first, nearest last = on top
            Radar.Blip bl = blips.get(i);
            double[] s = proj.at(bl.x(), bl.z());
            double sx = s[0], sy = s[1];
            if (sx < x + 2 || sx > x + SIZE - 2 || sy < y + 2 || sy > y + SIZE - 2) continue;
            if (round && Math.hypot(sx - midX, sy - midY) > R - 2) continue;
            if (faces && i < 150) {
                Radar.head(g, bl.e(), sx, sy, 3, bl.color(), bl.alpha());
                drewHead = true;
            } else {
                Radar.dot(g, sx, sy, 4, bl.color(), bl.alpha());
            }
        }


        // where you walked during the last minute
        Trail.clip = round ? new double[]{midX, midY, R - 1} : null;
        Trail.draw(g, dim, proj, pos.x, pos.z);
        Trail.clip = null;

        double[] navT = Nav.targetIn(dim); // dashed line to the marker you follow
        if (navT != null) {
            double[] s = proj.at(navT[0], navT[1]);
            Nav.drawPath(g, midX, midY, s[0], s[1], x, y, x + SIZE, y + SIZE, round);
        }

        for (Marker m : MarkerStore.MARKERS) {
            if (!m.dim.equals(dim)) continue;
            double[] s = proj.at(m.x + 0.5, m.z + 0.5);
            double sx = s[0], sy = s[1];
            boolean inside;
            double cx, cy;
            if (round) { // markers further away sit on the edge of the circle, in their direction
                double ddx = sx - midX, ddy = sy - midY, dd = Math.hypot(ddx, ddy), lim = R - 7;
                inside = dd <= lim;
                double kk = inside ? 1 : lim / dd;
                cx = midX + ddx * kk;
                cy = midY + ddy * kk;
            } else {
                inside = sx >= x + 7 && sx <= x + SIZE - 7 && sy >= y + 7 && sy <= y + SIZE - 7;
                cx = Mth.clamp(sx, x + 7, x + SIZE - 7);
                cy = Mth.clamp(sy, y + 7, y + SIZE - 7);
            }
            Icons.draw(g, m.icon, (float) cx, (float) cy, 12f * is * AtlasConfig.MARKER_SIZE.get() / 100f, m.color, inside ? 1f : 0.55f);
        }

        for (AbstractClientPlayer p : mc.level.players()) {
            if (other || !(AtlasConfig.SHOW_PLAYERS.get() && ServerRules.showPlayers())) break;
            if (p == mc.player || p.isSpectator() || p.isInvisible()) continue;
            Vec3 pp = p.getPosition(pt);
            double[] s = proj.at(pp.x, pp.z);
            double sx = s[0], sy = s[1];
            if (sx < x + 5 || sx > x + SIZE - 5 || sy < y + 5 || sy > y + SIZE - 5) continue;
            if (round && Math.hypot(sx - midX, sy - midY) > R - 5) continue;
            Ui.head(g, p, sx, sy, Ui.px(16), false, 0f);
        }
        Ui.head(g, mc.player, x + SIZE / 2.0, y + SIZE / 2.0, Ui.px(16), true,
                northUp ? mc.player.getViewYRot(pt) : -180f); // turning map: you always look up

        // faint letters of the sides of the world on the edge of the minimap (they slide along the frame as you turn)
        String[] sides = {"N", "E", "S", "W"};
        double[][] dirs = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        double rr = SIZE / 2.0 - 6;
        for (int i = 0; i < 4; i++) {
            double ux = dirs[i][0] * cos - dirs[i][1] * sin, uy = dirs[i][0] * sin + dirs[i][1] * cos;
            double to = round ? rr : rr / Math.max(Math.abs(ux), Math.abs(uy)); // on the edge: of the circle, or of the square
            double lx = midX + ux * to, ly = midY + uy * to;
            g.pose().pushPose();
            g.pose().translate((float) lx, (float) ly, 0f);
            g.pose().scale(0.6f, 0.6f, 1f);
            int col = 0xE6FFFFFF; // white with a shadow: readable on any terrain
            g.drawString(mc.font, sides[i], -mc.font.width(sides[i]) / 2, -4, col, true);
            g.pose().popPose();
        }
        g.disableScissor();


        if (other) { // which world this is
            g.pose().pushPose();
            g.pose().translate(x + 4, y + 4, 0);
            g.pose().scale(0.75f, 0.75f, 1f);
            g.drawString(mc.font, MapScreen.dimName(sel), 0, 0, 0xFFF3D98A, true);
            g.pose().popPose();
        } else if (caveAlpha > 0.04f) {
            int a = Mth.clamp((int) (caveAlpha * 255f), 5, 255);
            g.pose().pushPose();
            g.pose().translate(x + 4, y + 4, 0);
            g.pose().scale(0.75f, 0.75f, 1f);
            g.drawString(mc.font, Component.translatable("wayfarers_atlas.cave"), 0, 0, (a << 24) | 0xE8D8B0, true);
            g.pose().popPose();
        }

        // small, quiet coordinates under the map (numbers only)
        int ty = oy + outer + 4;
        if (showCoords) {
            String coords = mc.player.getBlockX() + "   " + mc.player.getBlockY() + "   " + mc.player.getBlockZ();
            g.pose().pushPose();
            g.pose().translate(ox + outer / 2f, ty, 0);
            g.pose().scale(0.75f, 0.75f, 1f);
            g.drawString(mc.font, coords, -mc.font.width(coords) / 2, 0, 0xFFE0D2AC, true);
            g.pose().popPose();
            ty += 8;
        }

        // biome you are standing in, under the coordinates (shrinks if the name is long)
        String bname = !showBiome ? "" : other
                ? BiomeNames.name(MapCache.biomeAt(key, Mth.floor(pos.x), Mth.floor(pos.z)))
                : BiomeNames.name(BiomeNames.at(mc.level, mc.player.blockPosition()));
        if (!bname.isEmpty()) {
            float bs = Math.min(0.75f, (outer + 10) / (float) Math.max(1, mc.font.width(bname)));
            g.pose().pushPose();
            g.pose().translate(ox + outer / 2f, ty, 0);
            g.pose().scale(bs, bs, 1f);
            g.drawString(mc.font, bname, -mc.font.width(bname) / 2, 0, 0xFFC9DC9A, true);
            g.pose().popPose();
        }
    }
}

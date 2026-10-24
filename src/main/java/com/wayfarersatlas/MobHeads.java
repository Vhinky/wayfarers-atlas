package com.wayfarersatlas;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.AgeableListModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HeadedModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Flat 2D creature icons made from the creature's own texture (the way Xaero's radar looks).
 *  - Creatures with a head: every cube of the head (face, snout, ears, horns, beak...) gives its FRONT side.
 *  - Creatures without a usable face (fish, squid, many modded animals with a thin or missing head): the whole
 *    body seen from the SIDE, like a little sticker of the animal.
 * The exact pieces of the texture are cut out and stacked (parts sticking out on top). Nothing is resampled:
 * one texture pixel becomes a square of whole screen pixels. The model is put into its resting pose first, so
 * animations never show. Creatures that do not use normal Minecraft models (e.g. GeckoLib) return false.
 */
public final class MobHeads {
    private enum View { FRONT, SIDE }

    private static final Set<EntityType<?>> FAILED = new HashSet<>();
    private static final Map<ResourceLocation, int[]> TEX_SIZE = new HashMap<>();
    private static final Set<String> HEAD_WORDS = Set.of("head", "ear", "ears", "nose", "snout", "beak", "bill",
            "horn", "horns", "jaw", "face", "mouth", "muzzle", "tusk", "tusks", "antler", "antlers", "skull", "hat");
    private static Method headPartsM, bodyPartsM;
    private static Field polygonsF, verticesF, normalF, uF, vF, childrenF;

    /** One visible side of a cube: a texture rectangle and where it goes (model pixels, as seen by the viewer). */
    private record Face(int u, int v, int uw, int vh, float x0, float y0, float x1, float y1, float depth) {}

    public static boolean canDraw(LivingEntity e) {
        return !FAILED.contains(e.getType());
    }

    /** Draws the icon centred on (cx, cy); 'pxPerTexel' real screen pixels per texture pixel. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean draw(GuiGraphics g, LivingEntity e, double cx, double cy, int pxPerTexel, float shade) {
        if (FAILED.contains(e.getType())) return false;
        Minecraft mc = Minecraft.getInstance();
        try {
            EntityRenderer<?> r = mc.getEntityRenderDispatcher().getRenderer(e);
            if (!(r instanceof LivingEntityRenderer<?, ?> lr)) throw new IllegalStateException("not a living renderer");
            EntityModel<?> model = lr.getModel();
            ResourceLocation tex = ((EntityRenderer) r).getTextureLocation(e);
            int[] ts = textureSize(mc, tex);

            List<Face> faces = List.of();
            List<ModelPart> head = headParts(model);
            if (!head.isEmpty()) faces = faces(head, ts, View.FRONT, true);
            if (faces.isEmpty() || tooThin(faces)) { // fish, squid & co: the whole creature from the side
                List<ModelPart> body = wholeParts(model);
                if (!body.isEmpty()) faces = faces(body, ts, View.SIDE, false);
            }
            if (faces.isEmpty()) throw new IllegalStateException("nothing to draw");

            float bx0 = Float.MAX_VALUE, by0 = Float.MAX_VALUE, bx1 = -Float.MAX_VALUE, by1 = -Float.MAX_VALUE;
            for (Face f : faces) {
                bx0 = Math.min(bx0, f.x0); by0 = Math.min(by0, f.y0);
                bx1 = Math.max(bx1, f.x1); by1 = Math.max(by1, f.y1);
            }
            // big icons (ghast, whole animals, bosses) get fewer screen pixels per texel
            int k = pxPerTexel;
            float size = Math.max(bx1 - bx0, by1 - by0);
            if (size > 12f) k = Math.max(1, Math.round(pxPerTexel * 12f / size));

            double sc = mc.getWindow().getGuiScale();
            int iw = Math.round((bx1 - bx0) * k), ih = Math.round((by1 - by0) * k);
            float left = Math.round(cx * sc - iw / 2.0), top = Math.round(cy * sc - ih / 2.0); // on the pixel grid

            // model pixels per texture pixel, from the biggest side (1 for vanilla textures, 0.5 for 2x packs)
            Face main = faces.get(0);
            for (Face f : faces) if (f.uw * f.vh > main.uw * main.vh) main = f;
            float mr = main.uw > 0 ? (main.x1 - main.x0) / main.uw : 1f;

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            PoseStack ps = g.pose();
            ps.pushPose();
            ps.scale((float) (1 / sc), (float) (1 / sc), 1f); // draw in real screen pixels
            ps.translate(left, top, 0f);
            // thin outline that follows the picture itself (transparent pixels stay clear): the texture drawn in
            // dark, one screen pixel left / right / up / down, then the face on top
            g.setColor(0f, 0f, 0f, 0.4f * Ui.alpha);
            int[][] off = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
            for (int[] o : off) {
                for (Face f : faces) {
                    if (f.uw <= 0 || f.vh <= 0) continue;
                    int w = Math.max(1, Math.round(f.uw * mr * k)), h = Math.max(1, Math.round(f.vh * mr * k));
                    int x = Math.round(((f.x0 + f.x1) / 2f - bx0) * k) - w / 2;
                    int y = Math.round(((f.y0 + f.y1) / 2f - by0) * k) - h / 2;
                    g.blit(tex, x + o[0], y + o[1], w, h, f.u, f.v, f.uw, f.vh, ts[0], ts[1]);
                }
            }
            g.setColor(shade, shade, shade, Ui.alpha); // darker for creatures far above / below you
            for (Face f : faces) {
                if (f.uw <= 0 || f.vh <= 0) continue;
                int w = Math.max(1, Math.round(f.uw * mr * k)), h = Math.max(1, Math.round(f.vh * mr * k));
                int x = Math.round(((f.x0 + f.x1) / 2f - bx0) * k) - w / 2;
                int y = Math.round(((f.y0 + f.y1) / 2f - by0) * k) - h / 2;
                g.blit(tex, x, y, w, h, f.u, f.v, f.uw, f.vh, ts[0], ts[1]);
            }
            ps.popPose();
            g.setColor(1f, 1f, 1f, Ui.alpha);
            return true;
        } catch (Throwable t) {
            g.setColor(1f, 1f, 1f, Ui.alpha);
            FAILED.add(e.getType());
            return false;
        }
    }

    /** A "face" that is a thin sliver (a fish seen from the front) does not work as an icon. */
    private static boolean tooThin(List<Face> faces) {
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (Face f : faces) {
            x0 = Math.min(x0, f.x0); y0 = Math.min(y0, f.y0);
            x1 = Math.max(x1, f.x1); y1 = Math.max(y1, f.y1);
        }
        float w = x1 - x0, h = y1 - y0;
        return w < 3f || h < 2f || w < h * 0.5f || h < w * 0.3f;
    }

    /**
     * The visible sides of all cubes of the given parts, farthest first (so what sticks out is drawn on top).
     * FRONT: seen from in front of the creature. SIDE: seen from its left side.
     */
    private static List<Face> faces(List<ModelPart> parts, int[] ts, View view, boolean straightenTop)
            throws ReflectiveOperationException {
        List<Face> out = new ArrayList<>();
        // resting pose of everything involved (no turning, looking around, chewing, swimming...), restored afterwards
        List<ModelPart> all = new ArrayList<>();
        for (ModelPart p : parts) p.getAllParts().forEach(q -> { if (!all.contains(q)) all.add(q); });
        List<PartPose> poses = new ArrayList<>(all.size());
        float[] scales = new float[all.size() * 3];
        for (int i = 0; i < all.size(); i++) {
            ModelPart q = all.get(i);
            poses.add(q.storePose());
            scales[i * 3] = q.xScale; scales[i * 3 + 1] = q.yScale; scales[i * 3 + 2] = q.zScale;
            q.resetPose();
            q.xScale = 1f; q.yScale = 1f; q.zScale = 1f;
        }
        if (straightenTop) for (ModelPart p : parts) { p.xRot = 0f; p.yRot = 0f; p.zRot = 0f; } // head looks ahead
        try {
            Vector3f v = new Vector3f();
            List<ReflectiveOperationException> err = new ArrayList<>();
            for (ModelPart p : parts) {
                if (!p.visible) continue;
                p.visit(new PoseStack(), (pose, path, index, cube) -> {
                    try {
                        Matrix4f m = pose.pose();
                        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
                        float near = -Float.MAX_VALUE;
                        for (int c = 0; c < 8; c++) {
                            m.transformPosition(((c & 1) == 0 ? cube.minX : cube.maxX) / 16f,
                                    ((c & 2) == 0 ? cube.minY : cube.maxY) / 16f,
                                    ((c & 4) == 0 ? cube.minZ : cube.maxZ) / 16f, v);
                            float sx, d;
                            if (view == View.FRONT) { // viewer in front (-z): model +x on the left
                                sx = -v.x * 16;
                                d = -v.z * 16;
                            } else {                  // viewer at the creature's left (+x): its front on the right
                                sx = -v.z * 16;
                                d = v.x * 16;
                            }
                            x0 = Math.min(x0, sx); x1 = Math.max(x1, sx);
                            y0 = Math.min(y0, v.y * 16); y1 = Math.max(y1, v.y * 16);
                            near = Math.max(near, d);
                        }
                        float[] uv = sideUv(cube, view);
                        if (uv == null) return;
                        int u = Math.round(uv[0] * ts[0]), vv = Math.round(uv[1] * ts[1]);
                        int uw = Math.round(uv[2] * ts[0]) - u, vh = Math.round(uv[3] * ts[1]) - vv;
                        out.add(new Face(u, vv, uw, vh, x0, y0, x1, y1, near));
                    } catch (ReflectiveOperationException ex) {
                        err.add(ex);
                    }
                });
            }
            if (!err.isEmpty()) throw err.get(0);
        } finally {
            for (int i = 0; i < all.size(); i++) {
                ModelPart q = all.get(i);
                q.loadPose(poses.get(i));
                q.xScale = scales[i * 3]; q.yScale = scales[i * 3 + 1]; q.zScale = scales[i * 3 + 2];
            }
        }
        out.sort((a, b) -> Float.compare(a.depth, b.depth)); // farthest first, nearest on top
        return out;
    }

    /** UV rectangle (u0, v0, u1, v1 in 0..1) of the cube side that faces the viewer, or null. */
    private static float[] sideUv(ModelPart.Cube cube, View view) throws ReflectiveOperationException {
        if (polygonsF == null) polygonsF = field(ModelPart.Cube.class, "polygons");
        Object[] polys = (Object[]) polygonsF.get(cube);
        for (Object poly : polys) {
            if (verticesF == null) {
                verticesF = field(poly.getClass(), "vertices");
                normalF = field(poly.getClass(), "normal");
            }
            Vector3f n = (Vector3f) normalF.get(poly);
            boolean facing = view == View.FRONT ? n.z() < -0.5f : n.x() > 0.5f;
            if (!facing) continue;
            Object[] verts = (Object[]) verticesF.get(poly);
            float u0 = 1, v0 = 1, u1 = 0, v1 = 0;
            for (Object vert : verts) {
                if (uF == null) {
                    uF = field(vert.getClass(), "u");
                    vF = field(vert.getClass(), "v");
                }
                float u = uF.getFloat(vert), vv = vF.getFloat(vert);
                u0 = Math.min(u0, u); u1 = Math.max(u1, u);
                v0 = Math.min(v0, vv); v1 = Math.max(v1, vv);
            }
            return new float[]{u0, v0, u1, v1};
        }
        return null;
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /** Real pixel size of a texture image (resource packs may use bigger textures). */
    private static int[] textureSize(Minecraft mc, ResourceLocation tex) throws Exception {
        int[] s = TEX_SIZE.get(tex);
        if (s != null) return s;
        try (InputStream in = mc.getResourceManager().open(tex); NativeImage img = NativeImage.read(in)) {
            s = new int[]{img.getWidth(), img.getHeight()};
        }
        TEX_SIZE.put(tex, s);
        return s;
    }

    public static void clearCache() {
        TEX_SIZE.clear();
        FAILED.clear();
    }

    // ------------------------------------------------------------------ finding the head

    /** Words of a part name: "leftEar" -> left, ear; "bone_head2" -> bone, head; "rearLeg" -> rear, leg. */
    private static List<String> words(String name) {
        List<String> out = new ArrayList<>();
        for (String w : name.replaceAll("([a-z])([A-Z])", "$1 $2").replaceAll("[^A-Za-z]+", " ").trim().split(" ")) {
            if (!w.isEmpty()) out.add(w.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static boolean isHeadName(String name) {
        for (String w : words(name)) if (HEAD_WORDS.contains(w)) return true;
        return false;
    }

    /** The head parts of a model, or an empty list. */
    private static List<ModelPart> headParts(EntityModel<?> model) throws ReflectiveOperationException {
        List<ModelPart> out = new ArrayList<>();
        if (model instanceof AgeableListModel<?> alm) { // cow, pig, sheep, chicken, wolf, horse...
            for (ModelPart p : invokeParts(alm, true)) out.add(p);
            if (!out.isEmpty()) return out;
        }
        if (model instanceof HeadedModel hm) { // zombie, skeleton, villager, illager...
            out.add(hm.getHead());
            if (model instanceof HumanoidModel<?> hum && hum.hat.visible) out.add(hum.hat);
            return out;
        }
        if (model instanceof HierarchicalModel<?> hier) { // creeper, spider, frog, fox, most modded (Blockbench) models
            ModelPart h = findNamed(hier.root(), 0);
            if (h != null) {
                out.add(h);
                return out;
            }
        }
        // any model: fields whose name is a head word (rabbit: head, ears, nose)
        for (Class<?> c = model.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != ModelPart.class || Modifier.isStatic(f.getModifiers())) continue;
                if (!isHeadName(f.getName())) continue;
                f.setAccessible(true);
                ModelPart p = (ModelPart) f.get(model);
                if (p != null && !out.contains(p)) out.add(p);
            }
        }
        return out;
    }

    /** Depth-first search for a child part named like a head ("head", "Head", "bone_head"...). */
    @SuppressWarnings("unchecked")
    private static ModelPart findNamed(ModelPart part, int depth) throws ReflectiveOperationException {
        if (depth > 8) return null;
        if (childrenF == null) childrenF = field(ModelPart.class, "children");
        Map<String, ModelPart> children = (Map<String, ModelPart>) childrenF.get(part);
        for (Map.Entry<String, ModelPart> e : children.entrySet()) { // exact "head" first
            if (e.getKey().equalsIgnoreCase("head")) return e.getValue();
        }
        for (Map.Entry<String, ModelPart> e : children.entrySet()) {
            List<String> w = words(e.getKey());
            if (w.contains("head") || w.contains("skull")) return e.getValue();
        }
        for (ModelPart child : children.values()) {
            ModelPart found = findNamed(child, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    /** All top-level parts of a model (for the side view of the whole creature). */
    private static List<ModelPart> wholeParts(EntityModel<?> model) throws ReflectiveOperationException {
        List<ModelPart> out = new ArrayList<>();
        if (model instanceof HierarchicalModel<?> hier) {
            out.add(hier.root());
            return out;
        }
        if (model instanceof AgeableListModel<?> alm) {
            for (ModelPart p : invokeParts(alm, true)) out.add(p);
            for (ModelPart p : invokeParts(alm, false)) out.add(p);
            return out;
        }
        for (Class<?> c = model.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != ModelPart.class || Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                ModelPart p = (ModelPart) f.get(model);
                if (p != null && !out.contains(p)) out.add(p);
            }
        }
        // drop parts that are children of other collected parts (they are drawn through their parent)
        List<ModelPart> top = new ArrayList<>(out);
        for (ModelPart p : out) p.getAllParts().skip(1).forEach(top::remove);
        return top;
    }

    @SuppressWarnings("unchecked")
    private static Iterable<ModelPart> invokeParts(AgeableListModel<?> m, boolean head) throws ReflectiveOperationException {
        if (headPartsM == null) {
            headPartsM = AgeableListModel.class.getDeclaredMethod("headParts");
            headPartsM.setAccessible(true);
            bodyPartsM = AgeableListModel.class.getDeclaredMethod("bodyParts");
            bodyPartsM.setAccessible(true);
        }
        return (Iterable<ModelPart>) (head ? headPartsM : bodyPartsM).invoke(m);
    }

    private MobHeads() {}
}

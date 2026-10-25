package com.wayfarersatlas;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.AgeableListModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HeadedModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;
import org.joml.Vector3f;

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
 * Flat creature icons made from the creature's own model and texture (the way Xaero's radar looks).
 *  - Creatures with a head: the head seen flat from the front. Long heads (horses, llamas, crocodiles...) are
 *    seen from the front and above: the top of the head with the ears, then the nose (like Xaero's radar).
 *  - Creatures without a usable head part: the whole creature from the front.
 *  - Squids: from the side (from the front they are a shapeless blob).
 * Every visible side of every cube is drawn as its own textured quad with the texture's own pixels; the model is
 * put into its resting pose first, so animations never show. Hidden parts (chests, saddles...) are left out.
 * Creatures that do not use normal Minecraft models (e.g. GeckoLib) return false.
 */
public final class MobHeads {
    /** Ink of the Atlas: the outline round heads and items. */
    static final float INK_R = 0x2B / 255f, INK_G = 0x1D / 255f, INK_B = 0x10 / 255f;

    private static final float LONG_YAW = 0f, LONG_PITCH = 55f; // long heads: from the front and above (like Xaero's)
    private static final Set<EntityType<?>> FAILED = new HashSet<>();
    private static final Map<EntityType<?>, Boolean> WHOLE = new HashMap<>();  // draw the whole creature
    private static final Map<EntityType<?>, Boolean> LONG = new HashMap<>();   // a long head (horse, llama...)
    private static final Set<String> HEAD_WORDS = Set.of("head", "ear", "ears", "nose", "snout", "beak", "bill",
            "horn", "horns", "jaw", "face", "mouth", "muzzle", "tusk", "tusks", "antler", "antlers", "skull", "hat");
    private static Method headPartsM, bodyPartsM;
    private static Field cubesF, polygonsF, verticesF, normalF, posF, uF, vF, childrenF;

    /** One visible side of a cube, already turned to the viewer: corners in model pixels (y down) with their UVs. */
    private record Quad(float[] x, float[] y, float[] u, float[] v, float depth) {}

    public static boolean canDraw(LivingEntity e) {
        return !FAILED.contains(e.getType());
    }

    /** Draws the icon centred on (cx, cy); 'pxPerTexel' real screen pixels per model pixel. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean draw(GuiGraphics g, LivingEntity e, double cx, double cy, int pxPerTexel, float shade) {
        if (FAILED.contains(e.getType())) return false;
        Minecraft mc = Minecraft.getInstance();
        try {
            EntityRenderer<?> r = mc.getEntityRenderDispatcher().getRenderer(e);
            if (!(r instanceof LivingEntityRenderer<?, ?> lr)) throw new IllegalStateException("not a living renderer");
            EntityModel<?> model = lr.getModel();
            ResourceLocation tex = ((EntityRenderer) r).getTextureLocation(e);

            List<Quad> quads = pick(e, model);
            if (quads.isEmpty()) throw new IllegalStateException("nothing to draw");

            float bx0 = Float.MAX_VALUE, by0 = Float.MAX_VALUE, bx1 = -Float.MAX_VALUE, by1 = -Float.MAX_VALUE;
            for (Quad q : quads) {
                for (int i = 0; i < 4; i++) {
                    bx0 = Math.min(bx0, q.x[i]); bx1 = Math.max(bx1, q.x[i]);
                    by0 = Math.min(by0, q.y[i]); by1 = Math.max(by1, q.y[i]);
                }
            }
            // All icons use the same texel size as long as they fit: a head is about 8 wide, a witch with her hat
            // is tall but not wide. Wider or much taller ones (jellyfish, ghasts, whole creatures) are made smaller.
            float w = bx1 - bx0, h = by1 - by0;
            float k = pxPerTexel;
            float fit = Math.min(w > 10f ? 10f / w : 1f, h > 18f ? 18f / h : 1f);
            if (fit < 1f) k = Math.max(0.35f, pxPerTexel * fit);
            double sc = mc.getWindow().getGuiScale();
            float midX = (bx0 + bx1) / 2f, midY = (by0 + by1) / 2f;
            float ox = Math.round(cx * sc), oy = Math.round(cy * sc); // centre on the pixel grid

            g.flush();
            PoseStack ps = g.pose();
            ps.pushPose();
            ps.scale((float) (1 / sc), (float) (1 / sc), 1f); // draw in real screen pixels
            Matrix4f m = ps.last().pose();
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            RenderSystem.setShaderTexture(0, tex);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f); // colours come with the vertices
            BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            // thin ink outline (like the markers) that follows the picture (transparent pixels stay clear), then the picture on top
            int[][] off = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
            float a = Ui.alpha;
            for (int[] o : off) {
                for (Quad q : quads) put(bb, m, q, k, midX, midY, ox + o[0], oy + o[1], INK_R, INK_G, INK_B, 0.85f * a);
            }
            for (Quad q : quads) put(bb, m, q, k, midX, midY, ox, oy, shade, shade, shade, a);
            MeshData md = bb.build();
            if (md != null) BufferUploader.drawWithShader(md);
            RenderSystem.enableCull();
            RenderSystem.setShaderColor(1f, 1f, 1f, Ui.alpha);
            ps.popPose();
            return true;
        } catch (Throwable t) {
            RenderSystem.enableCull();
            RenderSystem.setShaderColor(1f, 1f, 1f, Ui.alpha);
            FAILED.add(e.getType());
            return false;
        }
    }

    private static void put(BufferBuilder bb, Matrix4f m, Quad q, float k, float midX, float midY, float ox, float oy,
                            float r, float g, float b, float a) {
        for (int i = 0; i < 4; i++) {
            float x = ox + Math.round((q.x[i] - midX) * k), y = oy + Math.round((q.y[i] - midY) * k);
            bb.addVertex(m, x, y, 0f).setUv(q.u[i], q.v[i]).setColor(r, g, b, a);
        }
    }

    /** What makes the icon: squids from the side, everyone else from the front (head, or the whole creature). */
    private static List<Quad> pick(LivingEntity e, EntityModel<?> model) throws ReflectiveOperationException {
        EntityType<?> type = e.getType();
        if (e instanceof net.minecraft.world.entity.animal.Squid) { // squids and glow squids
            List<ModelPart> body = wholeParts(model);
            return body.isEmpty() ? List.of() : quads(body, 90f, 0f, false);
        }
        Boolean whole = WHOLE.get(type);
        List<ModelPart> head = Boolean.TRUE.equals(whole) ? List.of() : headParts(model);
        if (whole == null) {
            // no head found, or what is called "head" is only a small bit of the creature (claws, a tentacle):
            // then the whole creature from the front
            List<Quad> h = head.isEmpty() ? List.of() : quads(head, 0f, 0f, true);
            List<ModelPart> body = wholeParts(model);
            List<Quad> w = body.isEmpty() ? List.of() : quads(body, 0f, 0f, false);
            whole = !w.isEmpty() && (h.isEmpty() || tiny(h, w));
            WHOLE.put(type, whole);
            if (!whole) LONG.put(type, isLong(head));
        }
        if (whole) {
            List<ModelPart> body = wholeParts(model);
            return body.isEmpty() ? List.of() : quads(body, 0f, 0f, false);
        }
        // long heads (horses, llamas...) from the front and above: top of the head with the ears at the top, the
        // nose at the bottom (only the top and front sides show, so it stays flat); square heads straight from the front
        return LONG.getOrDefault(type, false) ? quads(head, LONG_YAW, LONG_PITCH, true) : quads(head, 0f, 0f, true);
    }

    /** A long face (horse, llama, crocodile): much deeper than wide, seen from the front it is only a nose. */
    private static boolean isLong(List<ModelPart> head) throws ReflectiveOperationException {
        List<Quad> front = quads(head, 0f, 0f, true);
        List<Quad> side = quads(head, 90f, 0f, true);
        if (front.isEmpty() || side.isEmpty()) return false;
        float[] f = box(front), s = box(side);
        return (s[2] - s[0]) > (f[2] - f[0]) * 1.6f;
    }

    /** Box around all corners: x0, y0, x1, y1 (model pixels). */
    private static float[] box(List<Quad> quads) {
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (Quad q : quads) {
            for (int i = 0; i < 4; i++) {
                x0 = Math.min(x0, q.x[i]); x1 = Math.max(x1, q.x[i]);
                y0 = Math.min(y0, q.y[i]); y1 = Math.max(y1, q.y[i]);
            }
        }
        return new float[]{x0, y0, x1, y1};
    }

    /** The "head" is only a small bit of the creature seen from the front (a claw, a tentacle, a nose). */
    private static boolean tiny(List<Quad> head, List<Quad> whole) {
        float[] h = box(head), w = box(whole);
        float hw = h[2] - h[0], hh = h[3] - h[1], ww = w[2] - w[0], wh = w[3] - w[1];
        if (ww <= 0 || wh <= 0) return false;
        return (hw < ww * 0.25f && hh < wh * 0.25f) || hw * hh < ww * wh * 0.06f;
    }

    /**
     * The visible sides of all cubes of the given parts as seen from 'angle' degrees (0 = straight from the front,
     * 90 = from the creature's left side), farthest first so what sticks out is drawn on top.
     */
    private static List<Quad> quads(List<ModelPart> parts, float yaw, float pitch, boolean straightenTop)
            throws ReflectiveOperationException {
        List<Quad> out = new ArrayList<>();
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
            // model space: x to the creature's left, y down, z backwards. yaw turns the viewer round to the
            // creature's left side, pitch lifts the viewer up so it looks down on the head
            double ry = Math.toRadians(yaw), rp = Math.toRadians(pitch);
            float sy = (float) Math.sin(ry), cy = (float) Math.cos(ry), sp = (float) Math.sin(rp), cp = (float) Math.cos(rp);
            Vector3f flat = new Vector3f(sy, 0f, -cy);                                   // towards the viewer, level
            Vector3f view = new Vector3f(flat.x * cp, -sp, flat.z * cp);                 // towards the viewer
            Vector3f right = new Vector3f(-cy, 0f, -sy);                                 // screen right
            Vector3f down = new Vector3f(flat.x * sp, cp, flat.z * sp);                  // screen down
            for (ModelPart p : parts) collect(p, new PoseStack(), view, right, down, out);
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

    /** Walks a part and its children (only the visible ones) and adds the sides that face the viewer. */
    @SuppressWarnings("unchecked")
    private static void collect(ModelPart p, PoseStack ps, Vector3f view, Vector3f right, Vector3f down, List<Quad> out)
            throws ReflectiveOperationException {
        if (!p.visible) return; // chests, saddles, armour bits that are switched off
        if (cubesF == null) {
            cubesF = field(ModelPart.class, "cubes");
            childrenF = field(ModelPart.class, "children");
        }
        ps.pushPose();
        p.translateAndRotate(ps);
        PoseStack.Pose pose = ps.last();
        if (!p.skipDraw) {
            Vector3f n = new Vector3f(), v = new Vector3f();
            for (Object cube : (List<Object>) cubesF.get(p)) {
                if (polygonsF == null) polygonsF = field(cube.getClass(), "polygons");
                for (Object poly : (Object[]) polygonsF.get(cube)) {
                    if (verticesF == null) {
                        verticesF = field(poly.getClass(), "vertices");
                        normalF = field(poly.getClass(), "normal");
                    }
                    pose.normal().transform((Vector3f) normalF.get(poly), n);
                    if (n.dot(view) < 0.2f) continue; // turned away or seen edge-on
                    Object[] verts = (Object[]) verticesF.get(poly);
                    if (verts.length != 4) continue;
                    float[] xs = new float[4], ys = new float[4], us = new float[4], vs = new float[4];
                    float depth = 0f;
                    for (int i = 0; i < 4; i++) {
                        Object vert = verts[i];
                        if (posF == null) {
                            posF = field(vert.getClass(), "pos");
                            uF = field(vert.getClass(), "u");
                            vF = field(vert.getClass(), "v");
                        }
                        Vector3f pos = (Vector3f) posF.get(vert);
                        pose.pose().transformPosition(pos.x() / 16f, pos.y() / 16f, pos.z() / 16f, v);
                        xs[i] = v.dot(right) * 16f;
                        ys[i] = v.dot(down) * 16f;
                        depth += v.dot(view) * 16f;
                        us[i] = uF.getFloat(vert);
                        vs[i] = vF.getFloat(vert);
                    }
                    out.add(new Quad(xs, ys, us, vs, depth / 4f));
                }
            }
        }
        for (ModelPart child : ((Map<String, ModelPart>) childrenF.get(p)).values()) collect(child, ps, view, right, down, out);
        ps.popPose();
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    public static void clearCache() {
        FAILED.clear();
        WHOLE.clear();
        LONG.clear();
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
            for (ModelPart p : invokeParts(alm, true)) out.addAll(innerHead(p));
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

    /**
     * Horses & co keep the neck and mane together with the head ("head_parts" holding "head", "mane",
     * "upper_mouth"...). Then only the real head is used, with the bits of the face next to it (mouth, ears); it is
     * long, so it is drawn a little from the side and you still see the ears and the shape of the head.
     */
    @SuppressWarnings("unchecked")
    private static List<ModelPart> innerHead(ModelPart p) throws ReflectiveOperationException {
        if (childrenF == null) childrenF = field(ModelPart.class, "children");
        Map<String, ModelPart> children = (Map<String, ModelPart>) childrenF.get(p);
        ModelPart head = null;
        for (Map.Entry<String, ModelPart> e : children.entrySet()) if (e.getKey().equalsIgnoreCase("head")) head = e.getValue();
        if (head == null) return List.of(p);
        List<ModelPart> out = new ArrayList<>();
        out.add(head);
        for (Map.Entry<String, ModelPart> e : children.entrySet()) {
            if (e.getValue() != head && isHeadName(e.getKey())) out.add(e.getValue());
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

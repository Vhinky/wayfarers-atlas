package com.wayfarersatlas;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Average colour of a block's top texture (so flowers are red, snow is white, modded blocks look right).
 * If the texture pixels cannot be read, callers fall back to vanilla map colours.
 */
public final class BlockColorCache {
    public record Info(int base, int tint) {}

    private static final Info NONE = new Info(-1, -1);
    private static final Map<BlockState, Info> CACHE = new HashMap<>();
    private static Method imageMethod;
    private static Field imageField;
    private static boolean reflectionFailed = false;

    public static Info get(BlockState state) {
        Info i = CACHE.get(state);
        if (i == null) {
            i = compute(state);
            CACHE.put(state, i);
        }
        return i;
    }

    public static void clear() {
        CACHE.clear();
        FLUIDS.clear();
    }

    private static final Map<Fluid, Integer> FLUIDS = new HashMap<>();

    /**
     * Colour of a (modded) fluid: the average of its still texture, multiplied by the fluid's tint.
     * Returns -1 if it cannot be determined.
     */
    public static int fluid(FluidState fs, BlockAndTintGetter level, BlockPos pos) {
        Fluid f = fs.getType();
        Integer base = FLUIDS.get(f);
        IClientFluidTypeExtensions ext = null;
        try {
            ext = IClientFluidTypeExtensions.of(fs);
        } catch (Throwable ignored) {
        }
        if (base == null) {
            base = -1;
            try {
                ResourceLocation still = ext == null ? null : ext.getStillTexture(fs, level, pos);
                if (still != null) {
                    TextureAtlasSprite sp = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(still);
                    if (!sp.contents().name().equals(MissingTextureAtlasSprite.getLocation())) base = average(sp, Mode.PLAIN);
                }
            } catch (Throwable t) {
                base = -1;
            }
            FLUIDS.put(f, base);
        }
        if (base < 0) return -1;
        int c = base;
        try {
            int tint = ext == null ? -1 : ext.getTintColor(fs, level, pos);
            if ((tint & 0xFFFFFF) != 0xFFFFFF) {
                int r = (((c >> 16) & 0xFF) * ((tint >> 16) & 0xFF)) / 255;
                int g = (((c >> 8) & 0xFF) * ((tint >> 8) & 0xFF)) / 255;
                int b = ((c & 0xFF) * (tint & 0xFF)) / 255;
                c = (r << 16) | (g << 8) | b;
            }
        } catch (Throwable ignored) {
        }
        return c;
    }

    private static Info compute(BlockState state) {
        try {
            BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
            RandomSource rand = RandomSource.create(42L);
            List<BakedQuad> quads = model.getQuads(state, Direction.UP, rand);
            if (quads.isEmpty()) quads = model.getQuads(state, null, rand);
            if (quads.isEmpty()) return NONE;
            // models can have several layers (e.g. untinted twigs + biome-tinted needles): prefer the tinted one
            BakedQuad q = quads.get(0);
            for (BakedQuad cand : quads) {
                if (cand.isTinted()) {
                    q = cand;
                    break;
                }
            }
            Mode mode = q.isTinted() ? Mode.TINTED
                    : (state.is(BlockTags.FLOWERS) || state.is(BlockTags.SMALL_FLOWERS)) ? Mode.FLOWER : Mode.PLAIN;
            int base = average(q.getSprite(), mode);
            if (base < 0) return NONE;
            return new Info(base, q.isTinted() ? q.getTintIndex() : -1);
        } catch (Throwable t) {
            return NONE;
        }
    }

    private static NativeImage imageOf(SpriteContents c) {
        if (reflectionFailed) return null;
        try {
            if (imageMethod == null && imageField == null) {
                try {
                    imageMethod = SpriteContents.class.getDeclaredMethod("getOriginalImage");
                    imageMethod.setAccessible(true);
                } catch (NoSuchMethodException e) {
                    imageField = SpriteContents.class.getDeclaredField("originalImage");
                    imageField.setAccessible(true);
                }
            }
            return (NativeImage) (imageMethod != null ? imageMethod.invoke(c) : imageField.get(c));
        } catch (ReflectiveOperationException | RuntimeException e) {
            reflectionFailed = true;
            LogUtils.getLogger().warn("Wayfarer's Atlas: cannot read block textures, using vanilla map colours", e);
            return null;
        }
    }

    private enum Mode { PLAIN, FLOWER, TINTED }

    /**
     * Average colour of a texture.
     * PLAIN: every pixel counts the same (leaves, stone, wood... - keeps them their real colour).
     * FLOWER: colourful pixels weigh more, so a flower is its petals and not its green stem.
     * TINTED: only the grey pixels that the biome colour is meant for (twigs or berries painted in do not count).
     */
    private static int average(TextureAtlasSprite sprite, Mode mode) {
        SpriteContents c = sprite.contents();
        NativeImage img = imageOf(c);
        if (img == null) return -1;
        int w = Math.min(c.width(), img.getWidth()), h = Math.min(c.height(), img.getHeight());
        double r = 0, g = 0, b = 0, wsum = 0;
        double gr = 0, gg = 0, gb = 0, gsum = 0; // grey pixels only (for TINTED)
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int px = img.getPixelRGBA(x, y); // ABGR
                if (((px >>> 24) & 0xFF) < 128) continue;
                int pr = px & 0xFF, pg = (px >> 8) & 0xFF, pb = (px >> 16) & 0xFF;
                int max = Math.max(pr, Math.max(pg, pb)), min = Math.min(pr, Math.min(pg, pb));
                double sat = max == 0 ? 0 : (max - min) / (double) max;
                double wt = mode == Mode.FLOWER ? 0.2 + sat * sat * 1.6 : 1.0;
                r += pr * wt;
                g += pg * wt;
                b += pb * wt;
                wsum += wt;
                if (sat < 0.22) {
                    gr += pr;
                    gg += pg;
                    gb += pb;
                    gsum++;
                }
            }
        }
        if (wsum == 0) return -1;
        if (mode == Mode.TINTED && gsum >= wsum * 0.3) {
            return ((int) (gr / gsum) << 16) | ((int) (gg / gsum) << 8) | (int) (gb / gsum);
        }
        return ((int) (r / wsum) << 16) | ((int) (g / wsum) << 8) | (int) (b / wsum);
    }

    private BlockColorCache() {}
}

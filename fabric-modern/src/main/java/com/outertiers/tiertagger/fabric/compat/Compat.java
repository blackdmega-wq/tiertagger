package com.outertiers.tiertagger.fabric.compat;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Tiny compatibility shim that papers over Vanilla API breakage between
 * Minecraft 1.21.1 and 1.21.11.
 *
 * <p><b>Root-cause of the "invisible icons / skin / nametag glyphs" bug
 * (fixed in v1.21.11.17):</b> all previous reflection paths searched for
 * methods by their <em>Yarn</em> name strings (e.g. {@code "drawTexture"},
 * {@code "withFont"}, {@code "createTexture"}, {@code "upload"},
 * {@code "draw"}). In a production Minecraft install the runtime namespace
 * is <em>intermediary</em>: every method is named {@code method_XXXXX}, so
 * none of the string checks ever matched and every icon/skin/glyph stayed
 * invisible.
 *
 * <p><b>Fix:</b> each reflective call site now has a companion "holder" inner
 * class that uses a <em>compile-time</em> (direct bytecode) call.  Loom
 * rewrites those references from Yarn → intermediary at build time, so the
 * intermediary-named methods are found correctly in production.  If the holder
 * fails to initialize (wrong MC version, missing class), the existing
 * string-based reflection paths are tried as a last-ditch fallback.
 */
public final class Compat {
    private Compat() {}

    /**
     * Draws a player face onto {@code ctx}. Tries the SkinTextures overload
     * first and falls back to the ResourceLocation overload. Handles arities 5..7
     * because 1.21.6+ introduced {@code boolean hat} / {@code boolean overlay}
     * trailing parameters.
     *
     * Note: In this mod, skin rendering in the profile/compare screens now goes
     * directly through Compat.drawTexture (with the mc-heads.net PNG), so this
     * method is retained only for potential future use.
     */
    public static void drawPlayerFace(GuiGraphics ctx, Object skin,
                                      ResourceLocation fallback, int x, int y, int size) {
        ResourceLocation tex = fallback;
        if (skin != null) {
            try {
                Object t = skin.getClass().getMethod("texture").invoke(skin);
                if (t instanceof ResourceLocation id) tex = id;
            } catch (Throwable ignored) {}
        }
        // Reflection-based fallback (handles any MC version in dev mode).
        // Pass 1: try SkinTextures-typed overloads (any arity from 5 to 7).
        try {
            for (Method m : PlayerFaceRenderer.class.getMethods()) {
                if (!"draw".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length < 5 || p.length > 7) continue;
                if (skin == null || !p[1].isInstance(skin)) continue;
                Object[] args = buildSkinDrawArgs(p, ctx, skin, x, y, size);
                m.invoke(null, args);
                return;
            }
        } catch (Throwable ignored) {}
        // Pass 2: try ResourceLocation-typed overloads (any arity 5..7).
        try {
            for (Method m : PlayerFaceRenderer.class.getMethods()) {
                if (!"draw".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length < 5 || p.length > 7) continue;
                if (tex == null || !p[1].isAssignableFrom(ResourceLocation.class)) continue;
                Object[] args = buildSkinDrawArgs(p, ctx, tex, x, y, size);
                m.invoke(null, args);
                return;
            }
        } catch (Throwable ignored) {
            // Caller has its own visual fallback.
        }
    }

    /** Fills the trailing boolean / int slots with sensible defaults so any 5/6/7-arg overload can be called. */
    private static Object[] buildSkinDrawArgs(Class<?>[] p, GuiGraphics ctx, Object skinOrTex,
                                              int x, int y, int size) {
        Object[] out = new Object[p.length];
        out[0] = ctx;
        out[1] = skinOrTex;
        out[2] = x;
        out[3] = y;
        out[4] = size;
        for (int i = 5; i < p.length; i++) {
            Class<?> t = p[i];
            if (t == boolean.class || t == Boolean.class)      out[i] = true;
            else if (t == int.class || t == Integer.class)     out[i] = size;
            else                                                out[i] = null;
        }
        return out;
    }

    /**
     * Resolves an {@link Item} by its registry id, transparently handling
     * both the {@code Item}-returning (1.21.1) and the
     * {@code Optional<RegistryEntry.Reference<Item>>}-returning (1.21.2+)
     * variants of {@code BuiltInRegistries.ITEM.get(...)}. Returns {@code null}
     * when the id is unknown.
     */
    public static Item lookupItem(ResourceLocation id) {
        if (id == null) return null;
        Object raw;
        try {
            raw = BuiltInRegistries.ITEM.get(id);
        } catch (Throwable t) {
            return null;
        }
        if (raw == null) return null;
        if (raw instanceof Item item) return item;
        if (raw instanceof Optional<?> opt) {
            if (opt.isEmpty()) return null;
            Object inner = opt.get();
            if (inner instanceof Item it2) return it2;
            try {
                Object value = inner.getClass().getMethod("value").invoke(inner);
                if (value instanceof Item it3) return it3;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * Reflective {@code GuiGraphics.drawTexture} that compiles unchanged
     * across all 1.21.x mappings.
     *
     * <p><b>Root-cause note (why icons &amp; skins were invisible on 1.21.6+):</b>
     * The previous code searched for the method by name {@code "drawTexture"},
     * which always failed in production because method names are intermediary
     * at runtime. {@link DrawTextureHolder} fixes this with a direct
     * compile-time call that Loom remaps correctly at build time.
     *
     * <p>Note: Only the modern 10-arg holder is provided. The legacy 9-arg form
     * {@code drawTexture(ResourceLocation,...)} was removed from GuiGraphics in
     * MC 1.21.11 and cannot be compiled into the same source file that targets
     * 1.21.11 Yarn mappings. Older-version builds fall through to the
     * reflection path below.
     */
    public static void drawTexture(GuiGraphics ctx, ResourceLocation tex,
                                   int x, int y, float u, float v,
                                   int w, int h, int texW, int texH) {
        if (ctx == null || tex == null) return;

        // PRIMARY: direct compile-time call (1.21.6+). Loom remaps this to
        // intermediary at build time — unlike the "drawTexture" string below.
        try {
            DrawTextureHolder.draw(ctx, tex, x, y, u, v, w, h, texW, texH);
            return;
        } catch (Throwable ignored) {}

        // FALLBACK: reflection-based (development mode / older MC versions).
        Method best = null;
        int bestArity = -1;
        for (Method m : GuiGraphics.class.getMethods()) {
            String n = m.getName();
            if (!"drawTexture".equals(n) && !"drawGuiTexture".equals(n)) continue;
            Class<?>[] p = m.getParameterTypes();
            int arity = p.length;
            if (arity < 9 || arity > 12) continue;
            boolean hasIdentifier = (arity == 9 && p[0] == ResourceLocation.class)
                                  || (arity >= 10 && p[1] == ResourceLocation.class);
            if (!hasIdentifier) continue;
            if (arity > bestArity) { best = m; bestArity = arity; }
        }
        if (best == null) return;

        try {
            Class<?>[] p = best.getParameterTypes();
            Object[] args;
            if (bestArity == 9) {
                args = new Object[] { tex, x, y, u, v, w, h, texW, texH };
            } else {
                Object pipelineOrLayer = defaultGuiRenderLayer(p[0], tex);
                if (pipelineOrLayer == null) return;
                args = new Object[bestArity];
                args[0] = pipelineOrLayer;
                args[1] = tex;
                args[2] = x; args[3] = y;
                args[4] = u; args[5] = v;
                args[6] = w; args[7] = h;
                args[8] = texW; args[9] = texH;
                for (int i = 10; i < bestArity; i++) {
                    Class<?> t = p[i];
                    if (t == int.class || t == Integer.class)          args[i] = 0xFFFFFFFF;
                    else if (t == float.class || t == Float.class)     args[i] = 1.0f;
                    else if (t == boolean.class || t == Boolean.class) args[i] = false;
                    else                                                args[i] = null;
                }
            }
            best.invoke(ctx, args);
        } catch (Throwable ignored) {
            // Visual fallback handled by caller (item icon or placeholder rect).
        }
    }

    /**
     * Direct compile-time call to the modern GuiGraphics.drawTexture overload
     * (MC 1.21.6+) that takes a RenderPipeline as its first argument. Loom
     * remaps both the method name and RenderPipelines.GUI_TEXTURED to their
     * correct intermediary identifiers at build time.
     *
     * <p>In MC 1.21.11 the method signature is 11-arg (with trailing color tint);
     * earlier 1.21.6–1.21.10 it was 10-arg. We use the 10-arg form here;
     * if the 10-arg overload was dropped in a future snapshot, the
     * {@code NoSuchMethodError} is caught and the reflection path takes over.
     *
     * Throws NoClassDefFoundError / NoSuchMethodError on MC 1.21.1–1.21.5
     * where RenderPipelines does not exist; callers catch Throwable.
     */
    private static final class DrawTextureHolder {
        static void draw(GuiGraphics ctx, ResourceLocation tex,
                         int x, int y, float u, float v,
                         int w, int h, int texW, int texH) {
            ctx.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED,
                            tex, x, y, u, v, w, h, texW, texH);
        }
    }

    private static Object defaultGuiRenderLayer(Class<?> firstParamType, ResourceLocation tex) {
        if (!java.util.function.Function.class.isAssignableFrom(firstParamType)) {
            try {
                Object pipeline = lookupRenderPipeline(firstParamType);
                if (pipeline != null) return pipeline;
            } catch (Throwable ignored) {}
        }

        try {
            Class<?> rp = tryClass("com.mojang.blaze3d.pipeline.RenderPipeline");
            if (rp != null && firstParamType.isAssignableFrom(rp)) {
                Object pipeline = lookupRenderPipeline(rp);
                if (pipeline != null) return pipeline;
            }
        } catch (Throwable ignored) {}

        try {
            Class<?> renderLayerCls = tryClass("net.minecraft.client.render.RenderLayer");
            if (renderLayerCls != null) {
                Method factory = null;
                for (String name : new String[] { "getGuiTextured", "getGuiTexturedOverlay", "guiTextured" }) {
                    try { factory = renderLayerCls.getMethod(name, ResourceLocation.class); break; }
                    catch (Throwable ignored) {}
                }
                if (factory != null) {
                    if (firstParamType.isAssignableFrom(renderLayerCls)) {
                        return factory.invoke(null, tex);
                    }
                    if (java.util.function.Function.class.isAssignableFrom(firstParamType)) {
                        final Method f = factory;
                        return (java.util.function.Function<ResourceLocation, Object>) (id -> {
                            try { return f.invoke(null, id); }
                            catch (Throwable t) { return null; }
                        });
                    }
                }
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private static volatile Object CACHED_GUI_PIPELINE;

    private static Object lookupRenderPipeline(Class<?> renderPipelineCls) {
        Object cached = CACHED_GUI_PIPELINE;
        if (cached != null && renderPipelineCls.isInstance(cached)) return cached;

        try {
            Object v = ModernPipelineHolder.GUI_TEXTURED;
            if (v != null && renderPipelineCls.isInstance(v)) {
                CACHED_GUI_PIPELINE = v;
                return v;
            }
        } catch (Throwable ignored) {}

        String[] containers = {
            "net.minecraft.client.gl.RenderPipelines",
            "net.minecraft.client.render.RenderPipelines",
            "com.mojang.blaze3d.pipeline.RenderPipelines",
            "net.minecraft.client.renderer.RenderPipelines"
        };
        String[] preferredFields = {
            "GUI_TEXTURED",
            "GUI_TEXTURED_PREMULTIPLIED_ALPHA",
            "GUI_TEXTURED_OVERLAY",
            "GUI"
        };
        for (String cls : containers) {
            Class<?> c = tryClass(cls);
            if (c == null) continue;
            for (String fn : preferredFields) {
                try {
                    java.lang.reflect.Field f = c.getField(fn);
                    Object v = f.get(null);
                    if (v != null && renderPipelineCls.isInstance(v)) {
                        CACHED_GUI_PIPELINE = v;
                        return v;
                    }
                } catch (Throwable ignored) {}
            }
            for (java.lang.reflect.Field f : c.getFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (!renderPipelineCls.isAssignableFrom(f.getType())) continue;
                try {
                    Object v = f.get(null);
                    if (v != null && renderPipelineCls.isInstance(v)) {
                        CACHED_GUI_PIPELINE = v;
                        return v;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /**
     * Isolated holder so {@code net.minecraft.client.gl.RenderPipelines} is only
     * loaded the first time something actually asks for the GUI pipeline.  If
     * the class doesn't exist on this MC version, accessing
     * {@link #GUI_TEXTURED} throws {@link NoClassDefFoundError} which the
     * caller catches — the reflection fallback then takes over.
     */
    private static final class ModernPipelineHolder {
        static final Object GUI_TEXTURED =
                net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED;
    }

    private static Class<?> tryClass(String name) {
        try { return Class.forName(name); } catch (Throwable t) { return null; }
    }

    /**
     * Allocates the GPU texture handle for a freshly-constructed
     * {@link DynamicTexture} and uploads its pixels.
     *
     * <p><b>Fix (v1.21.11.17):</b> the previous code searched for
     * {@code "createTexture"} and {@code "upload"} by their Yarn name strings,
     * which always failed in production because runtime method names are
     * intermediary. {@link TextureUploadHolder} now makes a direct compile-time
     * call that Loom remaps correctly at build time.
     *
     * <p>In MC 1.21.11, {@code createTexture(Supplier)} is private (the
     * constructor handles initialization internally), so {@link TextureUploadHolder}
     * only calls {@code upload()}. The reflection path also calls {@code upload()}
     * as a fallback.
     */
    public static void initGpuTexture(DynamicTexture tex, String label) {
        if (tex == null) return;

        // PRIMARY: direct compile-time call to upload() (Loom remaps at build time).
        // In MC 1.21.11, createTexture(Supplier) is private; upload() handles
        // the full initialization when called after construction.
        try {
            TextureUploadHolder.init(tex);
            return;
        } catch (Throwable ignored) {}

        // FALLBACK: reflection-based (development mode / unexpected version).
        // 1) createTexture(<name>) — exists on some 1.21.5-1.21.10 variants.
        try {
            for (Method m : DynamicTexture.class.getMethods()) {
                if (!"createTexture".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 1) continue;
                Object arg;
                if (p[0] == String.class) {
                    arg = label;
                } else if (Supplier.class.isAssignableFrom(p[0])) {
                    final String l = label;
                    arg = (Supplier<String>) () -> l;
                } else {
                    continue;
                }
                try {
                    m.invoke(tex, arg);
                    break;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        // 2) upload() — pushes the NativeImage pixels into the GPU texture.
        try {
            Method up = DynamicTexture.class.getMethod("upload");
            up.invoke(tex);
        } catch (Throwable ignored) {}
    }

    /**
     * Direct compile-time call to DynamicTexture.upload(). Loom
     * remaps the method name to intermediary at build time, so it is found
     * correctly in production — unlike the reflection approach using "upload".
     *
     * In MC 1.21.11, createTexture(Supplier) has private access; upload()
     * is the correct single public entry point for GPU texture initialization.
     */
    private static final class TextureUploadHolder {
        static void init(DynamicTexture tex) {
            tex.upload();
        }
    }

    /**
     * Reflective {@link DynamicTexture} constructor that compiles
     * across mapping changes:
     * <ul>
     *   <li>1.21.1–1.21.4: {@code new DynamicTexture(NativeImage)}</li>
     *   <li>1.21.5+: {@code new DynamicTexture(Supplier<String>, NativeImage)}</li>
     * </ul>
     * Returns {@code null} on any failure — callers must null-check.
     */
    public static DynamicTexture makeNativeImageTex(NativeImage img, String label) {
        if (img == null) return null;
        try {
            for (Constructor<?> c : DynamicTexture.class.getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 1 && p[0] == NativeImage.class) {
                    return (DynamicTexture) c.newInstance(img);
                }
                if (p.length == 2 && p[1] == NativeImage.class) {
                    Object first;
                    if (p[0] == String.class) {
                        first = label;
                    } else if (Supplier.class.isAssignableFrom(p[0])) {
                        final String l = label;
                        first = (Supplier<String>) () -> l;
                    } else {
                        continue;
                    }
                    return (DynamicTexture) c.newInstance(first, img);
                }
                if (p.length == 2 && p[0] == NativeImage.class) {
                    if (p[1] == boolean.class || p[1] == Boolean.class) {
                        return (DynamicTexture) c.newInstance(img, false);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}

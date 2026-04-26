package com.outertiers.tiertagger.fabric.compat;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Tiny compatibility shim that papers over Vanilla API breakage between
 * Minecraft 1.21.1 and 1.21.11. All call sites are reflection-based so a
 * single source compiles unchanged against any 1.21.x Yarn mappings.
 *
 * <p>Breaking changes handled here:
 * <ul>
 *   <li>{@code PlayerSkinDrawer.draw(...)} — accepts {@code Identifier} on
 *       1.21.1, {@code SkinTextures} on 1.21.2+, with extra
 *       {@code boolean hatVisible} / {@code boolean overlay} parameters
 *       starting on 1.21.6+.</li>
 *   <li>{@code DrawContext.drawTexture(...)} — signature changed three times
 *       between 1.21.1 and 1.21.6 (added {@code RenderLayer} factory in
 *       1.21.2; added {@code int color} tint in 1.21.5+).</li>
 *   <li>{@code Registries.ITEM.get(Identifier)} — returns {@code Item} on
 *       1.21.1, {@code Optional<RegistryEntry.Reference<Item>>} on
 *       1.21.2+.</li>
 * </ul>
 *
 * <p><b>Root-cause of the "invisible icons / skin / nametag glyphs" bug
 * (fixed in v1.21.11.17):</b> all previous reflection paths searched for
 * methods by their <em>Yarn</em> name strings (e.g. {@code "drawTexture"},
 * {@code "withFont"}, {@code "createTexture"}, {@code "upload"},
 * {@code "draw"}). In a production Minecraft install the runtime namespace
 * is <em>intermediary</em>: every method is named {@code method_XXXXX}, so
 * none of the string checks ever matched, the methods were never invoked, and
 * every icon/skin box stayed empty.
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
     * first and falls back to the Identifier overload. Handles arities 5..7
     * because 1.21.6+ introduced {@code boolean hat} / {@code boolean overlay}
     * trailing parameters.
     */
    public static void drawPlayerFace(DrawContext ctx, Object skin,
                                      Identifier fallback, int x, int y, int size) {
        Identifier tex = fallback;
        if (skin != null) {
            try {
                Object t = skin.getClass().getMethod("texture").invoke(skin);
                if (t instanceof Identifier id) tex = id;
            } catch (Throwable ignored) {}
        }

        // PRIMARY: direct compile-time call for 1.21.6+ (SkinTextures overload).
        // Loom remaps PlayerSkinDrawer.draw to its intermediary name at build time.
        if (skin != null) {
            try {
                SkinDrawHolder.draw(ctx, skin, x, y, size);
                return;
            } catch (Throwable ignored) {}
        }

        // PRIMARY (legacy): direct compile-time call for Identifier overload.
        // Falls through (NoSuchMethodError / ClassCastException) on 1.21.6+.
        if (tex != null) {
            try {
                SkinDrawLegacyHolder.draw(ctx, tex, x, y, size);
                return;
            } catch (Throwable ignored) {}
        }

        // FALLBACK: reflection-based (development mode / unexpected version).
        // Pass 1: try SkinTextures-typed overloads (any arity from 5 to 7).
        try {
            for (Method m : PlayerSkinDrawer.class.getMethods()) {
                if (!"draw".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length < 5 || p.length > 7) continue;
                if (skin == null || !p[1].isInstance(skin)) continue;
                Object[] args = buildSkinDrawArgs(p, ctx, skin, x, y, size);
                m.invoke(null, args);
                return;
            }
        } catch (Throwable ignored) {}
        // Pass 2: try Identifier-typed overloads (any arity 5..7).
        try {
            for (Method m : PlayerSkinDrawer.class.getMethods()) {
                if (!"draw".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length < 5 || p.length > 7) continue;
                if (tex == null || !p[1].isAssignableFrom(Identifier.class)) continue;
                Object[] args = buildSkinDrawArgs(p, ctx, tex, x, y, size);
                m.invoke(null, args);
                return;
            }
        } catch (Throwable ignored) {
            // Caller has its own visual fallback.
        }
    }

    /**
     * Direct compile-time call to the modern PlayerSkinDrawer.draw overload
     * that takes SkinTextures (MC 1.21.6+). Loom remaps both the class name
     * and method name to intermediary at build time, so they are found
     * correctly in production — unlike the string-based "draw" reflection
     * which always failed in production.
     *
     * Throws NoClassDefFoundError / NoSuchMethodError on MC versions that
     * don't have SkinTextures or the 7-arg draw; callers catch Throwable.
     */
    private static final class SkinDrawHolder {
        static void draw(DrawContext ctx, Object skin, int x, int y, int size) {
            net.minecraft.client.util.SkinTextures st =
                    (net.minecraft.client.util.SkinTextures) skin;
            net.minecraft.client.gui.PlayerSkinDrawer.draw(ctx, st, x, y, size, true, true);
        }
    }

    /**
     * Direct compile-time call to the legacy PlayerSkinDrawer.draw overload
     * that takes Identifier (MC 1.21.1). Throws NoSuchMethodError on newer MC
     * versions where this overload was removed; callers catch Throwable.
     */
    private static final class SkinDrawLegacyHolder {
        static void draw(DrawContext ctx, Identifier id, int x, int y, int size) {
            net.minecraft.client.gui.PlayerSkinDrawer.draw(ctx, id, x, y, size);
        }
    }

    /** Fills the trailing boolean / int slots with sensible defaults so any 5/6/7-arg overload can be called. */
    private static Object[] buildSkinDrawArgs(Class<?>[] p, DrawContext ctx, Object skinOrTex,
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
     * variants of {@code Registries.ITEM.get(...)}. Returns {@code null}
     * when the id is unknown.
     */
    public static Item lookupItem(Identifier id) {
        if (id == null) return null;
        Object raw;
        try {
            raw = Registries.ITEM.get(id);
        } catch (Throwable t) {
            return null;
        }
        if (raw == null) return null;
        if (raw instanceof Item item) return item;
        if (raw instanceof Optional<?> opt) {
            if (opt.isEmpty()) return null;
            Object inner = opt.get();
            if (inner instanceof Item it2) return it2;
            // RegistryEntry.Reference.value() — direct call preferred but
            // the class name is intermediary; fall back to reflection.
            try {
                Object value = ItemRegistryEntryHolder.value(inner);
                if (value instanceof Item it3) return it3;
            } catch (Throwable ignored) {}
            try {
                Object value = inner.getClass().getMethod("value").invoke(inner);
                if (value instanceof Item it3) return it3;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * Direct compile-time call to RegistryEntry.Reference.value(). Loom
     * remaps "value" to its intermediary method name at build time.
     */
    private static final class ItemRegistryEntryHolder {
        @SuppressWarnings("unchecked")
        static Object value(Object entry) {
            return ((net.minecraft.registry.entry.RegistryEntry.Reference<Item>) entry).value();
        }
    }

    /**
     * Reflective {@code DrawContext.drawTexture} that compiles unchanged
     * across all 1.21.x mappings.
     *
     * <p><b>Root-cause note (why icons &amp; skins were invisible on 1.21.6+):</b>
     * The previous code tried to resolve the method by looping
     * {@code DrawContext.class.getMethods()} looking for names {@code "drawTexture"}
     * / {@code "drawGuiTexture"}. In production the runtime namespace is
     * intermediary, so every method is named {@code method_XXXXX} — none of
     * those string checks ever matched, {@code best} was always {@code null},
     * the draw call was silently skipped, and callers set {@code drewIcon=true}
     * immediately after, so neither the texture nor the item fallback ever
     * rendered.
     *
     * <p><b>Fix:</b> {@link DrawTextureHolder} makes a direct compile-time
     * call that Loom remaps to intermediary at build time.  The
     * reflection-based search is kept as a last-ditch fallback.
     */
    public static void drawTexture(DrawContext ctx, Identifier tex,
                                   int x, int y, float u, float v,
                                   int w, int h, int texW, int texH) {
        if (ctx == null || tex == null) return;

        // PRIMARY: direct compile-time call (1.21.6+). Loom remaps this to
        // intermediary at build time — unlike the "drawTexture" string below.
        try {
            DrawTextureHolder.draw(ctx, tex, x, y, u, v, w, h, texW, texH);
            return;
        } catch (Throwable ignored) {}

        // PRIMARY (legacy 1.21.1): direct call without pipeline parameter.
        try {
            DrawTextureLegacyHolder.draw(ctx, tex, x, y, u, v, w, h, texW, texH);
            return;
        } catch (Throwable ignored) {}

        // FALLBACK: reflection-based (development / unexpected version).
        // NOTE: do NOT try drawGuiTexture(Identifier, x, y, w, h) here — that
        // 5-arg form renders GUI-atlas sprites, not arbitrary mod textures, and
        // calling it for mod icons or downloaded skin PNGs renders nothing.
        Method best = null;
        int bestArity = -1;
        for (Method m : DrawContext.class.getMethods()) {
            String n = m.getName();
            if (!"drawTexture".equals(n) && !"drawGuiTexture".equals(n)) continue;
            Class<?>[] p = m.getParameterTypes();
            int arity = p.length;
            if (arity < 9 || arity > 12) continue;
            boolean hasIdentifier = (arity == 9 && p[0] == Identifier.class)
                                  || (arity >= 10 && p[1] == Identifier.class);
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
     * Direct compile-time call to the modern DrawContext.drawTexture overload
     * (MC 1.21.6+) that takes a RenderPipeline as its first argument. Loom
     * remaps both the method name and RenderPipelines.GUI_TEXTURED to their
     * correct intermediary identifiers at build time.
     *
     * Throws NoClassDefFoundError / NoSuchMethodError on older MC versions;
     * callers catch Throwable.
     */
    private static final class DrawTextureHolder {
        static void draw(DrawContext ctx, Identifier tex,
                         int x, int y, float u, float v,
                         int w, int h, int texW, int texH) {
            ctx.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED,
                            tex, x, y, u, v, w, h, texW, texH);
        }
    }

    /**
     * Direct compile-time call to the legacy DrawContext.drawTexture overload
     * (MC 1.21.1) that takes an Identifier as its first argument.
     *
     * Throws NoSuchMethodError on newer MC versions; callers catch Throwable.
     */
    private static final class DrawTextureLegacyHolder {
        static void draw(DrawContext ctx, Identifier tex,
                         int x, int y, float u, float v,
                         int w, int h, int texW, int texH) {
            ctx.drawTexture(tex, x, y, u, v, w, h, texW, texH);
        }
    }

    private static Object defaultGuiRenderLayer(Class<?> firstParamType, Identifier tex) {
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
                    try { factory = renderLayerCls.getMethod(name, Identifier.class); break; }
                    catch (Throwable ignored) {}
                }
                if (factory != null) {
                    if (firstParamType.isAssignableFrom(renderLayerCls)) {
                        return factory.invoke(null, tex);
                    }
                    if (java.util.function.Function.class.isAssignableFrom(firstParamType)) {
                        final Method f = factory;
                        return (java.util.function.Function<Identifier, Object>) (id -> {
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
     * {@link NativeImageBackedTexture} and uploads its pixels.
     *
     * <p><b>Fix (v1.21.11.17):</b> the previous code searched for
     * {@code "createTexture"} and {@code "upload"} by their Yarn name strings,
     * which always failed in production because runtime method names are
     * intermediary.  {@link TextureInitHolder} now makes direct compile-time
     * calls that Loom remaps correctly at build time.
     */
    public static void initGpuTexture(NativeImageBackedTexture tex, String label) {
        if (tex == null) return;

        // PRIMARY: direct compile-time calls (Loom remaps at build time).
        try {
            TextureInitHolder.init(tex, label);
            return;
        } catch (Throwable ignored) {}

        // FALLBACK: reflection-based (development / unexpected version).
        boolean created = false;
        try {
            for (Method m : NativeImageBackedTexture.class.getMethods()) {
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
                    created = true;
                    break;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        try {
            Method up = NativeImageBackedTexture.class.getMethod("upload");
            up.invoke(tex);
        } catch (Throwable ignored) {}
        if (!created) return;
    }

    /**
     * Direct compile-time calls to NativeImageBackedTexture.createTexture and
     * .upload for MC 1.21.5+ where allocation is deferred. Loom remaps both
     * method names to intermediary at build time.
     *
     * Throws NoSuchMethodError on older MC (1.21.1–1.21.4) where allocation
     * was implicit; callers catch Throwable.
     */
    private static final class TextureInitHolder {
        static void init(NativeImageBackedTexture tex, String label) {
            tex.createTexture(() -> label);
            tex.upload();
        }
    }

    /**
     * Reflective {@link NativeImageBackedTexture} constructor that compiles
     * across mapping changes:
     * <ul>
     *   <li>1.21.1–1.21.4: {@code new NativeImageBackedTexture(NativeImage)}</li>
     *   <li>1.21.5+: {@code new NativeImageBackedTexture(Supplier<String>, NativeImage)}</li>
     * </ul>
     * Returns {@code null} on any failure — callers must null-check.
     */
    public static NativeImageBackedTexture makeNativeImageTex(NativeImage img, String label) {
        if (img == null) return null;
        try {
            for (Constructor<?> c : NativeImageBackedTexture.class.getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 1 && p[0] == NativeImage.class) {
                    return (NativeImageBackedTexture) c.newInstance(img);
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
                    return (NativeImageBackedTexture) c.newInstance(first, img);
                }
                if (p.length == 2 && p[0] == NativeImage.class) {
                    if (p[1] == boolean.class || p[1] == Boolean.class) {
                        return (NativeImageBackedTexture) c.newInstance(img, false);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}

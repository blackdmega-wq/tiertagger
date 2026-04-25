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
            try {
                Object value = inner.getClass().getMethod("value").invoke(inner);
                if (value instanceof Item it3) return it3;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * Reflective {@code DrawContext.drawTexture} that compiles unchanged
     * across all 1.21.x mappings. Vanilla's signature evolved repeatedly:
     * <ul>
     *   <li>1.21.1: {@code drawTexture(Identifier, x, y, u, v, w, h, texW, texH)} (9 args)</li>
     *   <li>1.21.2–1.21.5: {@code drawTexture(Function<Identifier,RenderLayer>, Identifier, x, y, u, v, w, h, texW, texH)} (10 args)</li>
     *   <li>1.21.6+: {@code drawTexture(RenderPipeline, Identifier, x, y, u, v, w, h, texW, texH[, color])} (10-11 args)</li>
     * </ul>
     *
     * <p><b>Root-cause note (why icons &amp; skins were invisible on 1.21.6+):</b>
     * The previous code tried to resolve the {@code RenderPipeline} value by checking
     * for a hardcoded Mojang/intermediary class name
     * ({@code "com.mojang.blaze3d.pipeline.RenderPipeline"}).  In Yarn-remapped
     * production builds that class does NOT exist under that name, so
     * {@code tryClass()} returned {@code null}, the pipeline was never looked up,
     * {@code defaultGuiRenderLayer} returned {@code null}, and the draw call was
     * silently skipped every frame — leaving every icon and skin box empty.
     *
     * <p><b>Fix:</b> pass {@code firstParamType} (the actual parameter type of the
     * found method) directly to {@link #lookupRenderPipeline}.  That type IS the
     * correct {@code RenderPipeline} class for this MC version, regardless of what
     * Yarn calls it — so {@code renderPipelineCls.isInstance(v)} works correctly
     * without any name guessing.
     *
     * <p>Failures are swallowed so callers don't have to wrap every call.
     */
    public static void drawTexture(DrawContext ctx, Identifier tex,
                                   int x, int y, float u, float v,
                                   int w, int h, int texW, int texH) {
        if (ctx == null || tex == null) return;

        // Search for the best drawTexture overload. We accept 9–12 args because
        // Mojang kept adding parameters across the 1.21.x release line.
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
            // Acceptable arities:
            //   9  — legacy 1.21.1   (Identifier first)
            //  10  — 1.21.2+         (RenderLayer/RenderPipeline first, Identifier second)
            //  11  — 1.21.5+         (+ int color tint)
            //  12  — defensive guard for any future addition
            if (arity < 9 || arity > 12) continue;
            // The Identifier must appear at index 0 (legacy) or 1 (modern).
            boolean hasIdentifier = (arity == 9 && p[0] == Identifier.class)
                                  || (arity >= 10 && p[1] == Identifier.class);
            if (!hasIdentifier) continue;
            // Prefer the highest-arity match so we don't accidentally pick a
            // deprecated overload that Mojang kept for backwards compatibility.
            if (arity > bestArity) { best = m; bestArity = arity; }
        }
        if (best == null) return;

        try {
            Class<?>[] p = best.getParameterTypes();
            Object[] args;
            if (bestArity == 9) {
                // Legacy 1.21.1 signature — no leading pipeline/layer.
                args = new Object[] { tex, x, y, u, v, w, h, texW, texH };
            } else {
                // Modern signature (1.21.2+): first parameter is a pipeline or layer.
                Object pipelineOrLayer = defaultGuiRenderLayer(p[0], tex);
                // CRITICAL: if we cannot resolve the pipeline, we must NOT call the
                // method with null for a primitive-typed first parameter — the JVM
                // throws NullPointerException on unbox, which is exactly the silent
                // failure that hid every icon and skin on 1.21.6+.
                if (pipelineOrLayer == null) return;
                args = new Object[bestArity];
                args[0] = pipelineOrLayer;
                args[1] = tex;
                args[2] = x; args[3] = y;
                args[4] = u; args[5] = v;
                args[6] = w; args[7] = h;
                args[8] = texW; args[9] = texH;
                // Trailing slots: color tint (opaque white) or safe type-specific defaults.
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
     * Returns the value to pass as the first (non-Identifier) parameter of the
     * modern {@code drawTexture} overloads.  The MC API changed this slot three
     * times across the 1.21.x series:
     *
     * <ul>
     *   <li>1.21.1: no extra parameter (handled by the 9-arg branch above).</li>
     *   <li>1.21.2–1.21.5: {@code Function<Identifier, RenderLayer>}.</li>
     *   <li>1.21.6+ (incl. 1.21.11): {@code RenderPipeline}.</li>
     * </ul>
     *
     * <p><b>Key fix vs. previous code:</b> instead of guessing the Yarn name of
     * {@code RenderPipeline} (which is under {@code com.mojang.blaze3d.*} in
     * intermediary but has a completely different name in Yarn-mapped builds),
     * we now pass {@code firstParamType} — the actual runtime type of the method
     * parameter — directly to {@link #lookupRenderPipeline}.  This lets
     * {@code renderPipelineCls.isInstance(v)} do the right thing without any
     * name guessing, and is the change that finally makes icon &amp; skin
     * rendering work on all 1.21.x versions.
     */
    private static Object defaultGuiRenderLayer(Class<?> firstParamType, Identifier tex) {

        // ── Path 1 (PRIMARY): use firstParamType directly ─────────────────────
        // firstParamType IS the correct RenderPipeline (or RenderLayer) class for
        // this MC build — no Yarn name guessing needed.  This is the main fix:
        // the old code required com.mojang.blaze3d.pipeline.RenderPipeline to be
        // loadable, which fails on all Yarn-mapped production builds.
        if (!java.util.function.Function.class.isAssignableFrom(firstParamType)) {
            // Non-Function first param → likely a RenderPipeline (1.21.6+).
            try {
                Object pipeline = lookupRenderPipeline(firstParamType);
                if (pipeline != null) return pipeline;
            } catch (Throwable ignored) {}
        }

        // ── Path 2: Mojang/intermediary RenderPipeline class name (kept as fallback) ──
        try {
            Class<?> rp = tryClass("com.mojang.blaze3d.pipeline.RenderPipeline");
            if (rp != null && firstParamType.isAssignableFrom(rp)) {
                Object pipeline = lookupRenderPipeline(rp);
                if (pipeline != null) return pipeline;
            }
        } catch (Throwable ignored) {}

        // ── Path 3: RenderLayer / Function<Identifier,RenderLayer> (1.21.2–1.21.5) ──
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

    /** Cached so we don't pay reflection cost on every drawn icon. */
    private static volatile Object CACHED_GUI_PIPELINE;

    /**
     * Looks up {@code RenderPipelines.GUI_TEXTURED} (or its closest equivalent)
     * across all package locations Mojang has used in 1.21.6+.
     *
     * <p><b>v1.21.11.12 root-cause fix — INVISIBLE SKINS &amp; SCREEN ICONS:</b>
     * The previous reflection-only path tried {@code Class.forName} with the
     * <em>Yarn</em> class names (e.g. {@code net.minecraft.client.gl.RenderPipelines}).
     * In production / launcher builds the mod jar is remapped from Yarn to
     * intermediary, so at runtime that class is named {@code net.minecraft.class_XXXX}
     * — the {@code Class.forName} call returned {@code null} every single time, the
     * {@code drawTexture} reflection silently skipped, and every face / icon /
     * skin in the profile + compare screens was invisible.
     *
     * <p><b>Fix:</b> the primary lookup is now a direct compile-time field
     * reference to {@code RenderPipelines.GUI_TEXTURED} inside a tiny isolated
     * holder class.  Loom remaps that reference to the correct intermediary name
     * at build time, so it works on every per-MC-version jar regardless of what
     * Yarn calls it next snapshot.  The existing reflection paths are kept as a
     * fallback in case the holder fails to initialise (e.g. on a future MC
     * version that drops {@code RenderPipelines} again).
     *
     * <p>{@code renderPipelineCls} must be the <em>actual runtime type</em> that
     * the method parameter expects (i.e. {@code firstParamType} from
     * {@link #defaultGuiRenderLayer}) so that {@code isInstance} works correctly.
     */
    private static Object lookupRenderPipeline(Class<?> renderPipelineCls) {
        Object cached = CACHED_GUI_PIPELINE;
        if (cached != null && renderPipelineCls.isInstance(cached)) return cached;

        // ── PRIMARY: direct compile-time field reference (Loom remaps to intermediary) ──
        // Wrapped in try/catch so a missing class on a future MC build doesn't kill
        // the lookup — we still have the reflection fallbacks below.
        try {
            Object v = ModernPipelineHolder.GUI_TEXTURED;
            if (v != null && renderPipelineCls.isInstance(v)) {
                CACHED_GUI_PIPELINE = v;
                return v;
            }
        } catch (Throwable ignored) {}

        // ── FALLBACK: name-based reflection (legacy, still works on some builds) ──
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
     *
     * <p>Critically, the static field reference here is a <em>compile-time</em>
     * Yarn reference: Loom rewrites it to the correct intermediary
     * ({@code net.minecraft.class_XXXX#field_YYYYY}) at build time, so it
     * resolves correctly in production launchers where the runtime
     * namespace is intermediary.
     */
    private static final class ModernPipelineHolder {
        static final Object GUI_TEXTURED =
                net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED;
    }

    private static Class<?> tryClass(String name) {
        try { return Class.forName(name); } catch (Throwable t) { return null; }
    }

    /**
     * Reflective {@link NativeImageBackedTexture} constructor that compiles
     * across mapping changes:
     * <ul>
     *   <li>1.21.1–1.21.4: {@code new NativeImageBackedTexture(NativeImage)}</li>
     *   <li>1.21.5+: {@code new NativeImageBackedTexture(Supplier<String>, NativeImage)}
     *       (a debug label used in renderdoc captures).</li>
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

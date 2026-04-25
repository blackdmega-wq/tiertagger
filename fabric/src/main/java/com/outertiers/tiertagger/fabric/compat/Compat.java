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
            if (t == boolean.class || t == Boolean.class)      out[i] = true;     // hat / overlay defaults to visible
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
     *   <li>1.21.2+: {@code drawTexture(Function<Identifier,RenderLayer>, Identifier, x, y, u, v, w, h, texW, texH)} (10 args)</li>
     *   <li>1.21.5+: same as above plus a trailing {@code int color} tint (11 args).</li>
     * </ul>
     * <p><b>Fast path:</b> when {@code u == 0 && v == 0} (full-texture blit, no UV offset),
     * this method first tries {@code drawGuiTexture(Identifier, x, y, w, h)} — a stable
     * 5-argument overload present in every 1.21.x build that needs no RenderLayer or
     * RenderPipeline. This is the path that finally fixes skin / icon rendering on
     * MC 1.21.6+, where the multi-arg reflection paths kept failing silently.
     * <p>
     * Failures are swallowed so callers don't have to wrap every call.
     */
    public static void drawTexture(DrawContext ctx, Identifier tex,
                                   int x, int y, float u, float v,
                                   int w, int h, int texW, int texH) {
        if (ctx == null || tex == null) return;

        // ── Fast path: drawGuiTexture(Identifier, int, int, int, int) ────────
        // This 5-arg overload is stable across ALL 1.21.x versions and requires
        // no RenderLayer / RenderPipeline. We use it whenever we want the full
        // texture (u == 0, v == 0), which covers every caller in this mod
        // (skin heads, gamemode icons). This is the fix for the persistent
        // "skins don't appear" bug on MC 1.21.6+.
        if (u == 0 && v == 0) {
            for (Method m : DrawContext.class.getMethods()) {
                if (!"drawGuiTexture".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                // (Identifier, int, int, int, int) — pure 5-arg form
                if (p.length == 5
                        && p[0] == Identifier.class
                        && p[1] == int.class && p[2] == int.class
                        && p[3] == int.class && p[4] == int.class) {
                    try { m.invoke(ctx, tex, x, y, w, h); return; }
                    catch (Throwable ignored) {}
                }
            }
        }

        // ── Fallback: full reflection search across 9-12 arg overloads ───────
        // We try multiple method names because Mojang has shuffled them over
        // 1.21.x: "drawTexture" (1.21.1-1.21.10), and some snapshots renamed
        // the GUI variant to "drawGuiTexture". Both signatures are otherwise
        // identical, so we accept either.
        Method best = null;
        int bestArity = -1;
        for (Method m : DrawContext.class.getMethods()) {
            String n = m.getName();
            if (!"drawTexture".equals(n) && !"drawGuiTexture".equals(n)) continue;
            Class<?>[] p = m.getParameterTypes();
            int arity = p.length;
            // Acceptable: 9 (legacy), 10 (1.21.2+), 11 (1.21.5+ with color tint),
            // 12 (defensive — some snapshots add another scalar).
            if (arity < 9 || arity > 12) continue;
            // The Identifier must appear at index 0 (legacy) or 1 (modern).
            boolean hasIdentifier = (arity == 9 && p[0] == Identifier.class)
                                  || (arity >= 10 && p[1] == Identifier.class);
            if (!hasIdentifier) continue;
            // Prefer the highest-arity match (newer signature) so we don't
            // accidentally pick a deprecated overload that was kept for compat.
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
                // CRITICAL: the modern (1.21.6+) overload has a primitive
                // RenderPipeline first parameter. Reflection.invoke throws
                // NullPointerException if we pass null for it — which is
                // exactly the silent failure mode that hid every gamemode
                // icon in compare / profile. Bail out cleanly here so the
                // caller's item-icon fallback gets a chance to draw.
                if (pipelineOrLayer == null) return;
                args = new Object[bestArity];
                args[0] = pipelineOrLayer;
                args[1] = tex;
                args[2] = x; args[3] = y;
                args[4] = u; args[5] = v;
                args[6] = w; args[7] = h;
                args[8] = texW; args[9] = texH;
                // Trailing slots default to opaque-white tint / safe defaults.
                for (int i = 10; i < bestArity; i++) {
                    Class<?> t = p[i];
                    if (t == int.class || t == Integer.class)     args[i] = 0xFFFFFFFF;
                    else if (t == float.class || t == Float.class) args[i] = 1.0f;
                    else if (t == boolean.class || t == Boolean.class) args[i] = false;
                    else                                            args[i] = null;
                }
            }
            best.invoke(ctx, args);
        } catch (Throwable ignored) {
            // Visual fallback handled by caller.
        }
    }

    /**
     * Resolves the value to pass for the first non-Identifier parameter of the
     * modern {@code drawTexture}/{@code drawGuiTexture} overloads. The MC API
     * went through three incompatible shapes in the 1.21.x line:
     *
     * <ul>
     *   <li>1.21.1: single overload with no extra leading parameter (handled
     *       by the 9-arg branch in {@link #drawTexture}).</li>
     *   <li>1.21.2-1.21.5: leading {@code Function<Identifier, RenderLayer>}
     *       — typical value is {@code RenderLayer::getGuiTextured}.</li>
     *   <li>1.21.6+ (incl. 1.21.11): leading {@code RenderPipeline} —
     *       typical value is {@code RenderPipelines.GUI_TEXTURED}. This is
     *       the variant our prior reflection failed to satisfy, which made
     *       every gamemode icon on the compare/profile screens silently
     *       disappear.</li>
     * </ul>
     */
    private static Object defaultGuiRenderLayer(Class<?> firstParamType, Identifier tex) {
        // ── Path 1: RenderPipeline (1.21.6+ inc. 1.21.11) ────────────────
        try {
            Class<?> rp = tryClass("com.mojang.blaze3d.pipeline.RenderPipeline");
            if (rp != null && firstParamType.isAssignableFrom(rp)) {
                Object pipeline = lookupRenderPipeline(rp);
                if (pipeline != null) return pipeline;
            }
        } catch (Throwable ignored) {}

        // ── Path 2: RenderLayer / Function<Identifier,RenderLayer> (1.21.2-1.21.5) ──
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
     * across the package locations Mojang has shipped it from in 1.21.6+. The
     * field name is stable across snapshots ({@code GUI_TEXTURED}) but its
     * containing class moved between {@code net.minecraft.client.gl.*},
     * {@code net.minecraft.client.render.*}, and {@code com.mojang.blaze3d.*}
     * between yarn builds.
     */
    private static Object lookupRenderPipeline(Class<?> renderPipelineCls) {
        Object cached = CACHED_GUI_PIPELINE;
        if (cached != null) return cached;
        String[] containers = {
            "net.minecraft.client.gl.RenderPipelines",
            "net.minecraft.client.render.RenderPipelines",
            "com.mojang.blaze3d.pipeline.RenderPipelines"
        };
        String[] preferredFields = {
            // Listed best-first: GUI_TEXTURED is the standard for opaque GUI sprites.
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
            // Last resort: any public static field whose declared type is RenderPipeline.
            for (java.lang.reflect.Field f : c.getFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (!renderPipelineCls.isAssignableFrom(f.getType())) continue;
                try {
                    Object v = f.get(null);
                    if (v != null) {
                        CACHED_GUI_PIPELINE = v;
                        return v;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static Class<?> tryClass(String name) {
        try { return Class.forName(name); } catch (Throwable t) { return null; }
    }

    /**
     * Reflective {@link NativeImageBackedTexture} constructor that compiles
     * across mapping changes:
     * <ul>
     *   <li>1.21.1-1.21.4: {@code new NativeImageBackedTexture(NativeImage)}</li>
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
                    // Hypothetical (NativeImage, boolean) variant — pass false.
                    if (p[1] == boolean.class || p[1] == Boolean.class) {
                        return (NativeImageBackedTexture) c.newInstance(img, false);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}

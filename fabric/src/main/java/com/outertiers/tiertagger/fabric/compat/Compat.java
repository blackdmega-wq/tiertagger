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
     * We pick whichever overload exists on the runtime DrawContext class.
     * Failures are swallowed so callers don't have to wrap every call.
     */
    public static void drawTexture(DrawContext ctx, Identifier tex,
                                   int x, int y, float u, float v,
                                   int w, int h, int texW, int texH) {
        if (ctx == null || tex == null) return;
        // Prefer the newer overloads first (modern MC); fall back to the older 9-arg form.
        try {
            Method best = null;
            int bestArity = -1;
            for (Method m : DrawContext.class.getMethods()) {
                if (!"drawTexture".equals(m.getName())) continue;
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
            Class<?>[] p = best.getParameterTypes();
            Object[] args;
            if (bestArity == 9) {
                args = new Object[] { tex, x, y, u, v, w, h, texW, texH };
            } else {
                args = new Object[bestArity];
                args[0] = defaultGuiRenderLayer(p[0], tex);
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

    private static Object defaultGuiRenderLayer(Class<?> firstParamType, Identifier tex) {
        // 1.21.2 added an explicit Function<Identifier, RenderLayer> first
        // parameter. The standard choice is RenderLayer::getGuiTextured.
        try {
            Class<?> renderLayerCls = Class.forName("net.minecraft.client.render.RenderLayer");
            Method factory = null;
            // Method name varies a tiny bit between mappings; try the common ones.
            for (String name : new String[] { "getGuiTextured", "getGuiTexturedOverlay" }) {
                try { factory = renderLayerCls.getMethod(name, Identifier.class); break; }
                catch (Throwable ignored) {}
            }
            if (factory == null) return null;
            // If the parameter type is Function-like, return a method ref;
            // if it's already RenderLayer, return the resolved instance.
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
        } catch (Throwable ignored) {}
        return null;
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

package com.outertiers.tiertagger.fabric.compat;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Tiny compatibility shim that papers over Vanilla API breakage between
 * Minecraft 1.21.1 and 1.21.2+. Both call sites are reflection-based so a
 * single source compiles unchanged against any 1.21.x Yarn mappings.
 *
 * <p>Two breaking changes are handled here:
 * <ul>
 *   <li>{@code PlayerSkinDrawer.draw(...)} — accepts {@code Identifier} on
 *       1.21.1, {@code SkinTextures} on 1.21.2+.</li>
 *   <li>{@code Registries.ITEM.get(Identifier)} — returns {@code Item} on
 *       1.21.1, {@code Optional<RegistryEntry.Reference<Item>>} on
 *       1.21.2+. The unchanged source compiles because the result is
 *       captured into {@code Object}; the runtime check unwraps either
 *       shape.</li>
 * </ul>
 */
public final class Compat {
    private Compat() {}

    /**
     * Draws a player face onto {@code ctx}. Tries the 1.21.2+ overload
     * (accepting {@link SkinTextures}) first and falls back to the 1.21.1
     * overload (accepting {@link Identifier}). When {@code skin} is
     * {@code null} only the {@code Identifier} fallback can be used.
     */
    public static void drawPlayerFace(DrawContext ctx, SkinTextures skin,
                                      Identifier fallback, int x, int y, int size) {
        Identifier tex = (skin != null && skin.texture() != null) ? skin.texture() : fallback;
        try {
            for (Method m : PlayerSkinDrawer.class.getDeclaredMethods()) {
                if (!"draw".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 5) continue;
                if (skin != null && p[1].isAssignableFrom(SkinTextures.class)) {
                    m.invoke(null, ctx, skin, x, y, size);
                    return;
                }
                if (tex != null && p[1].isAssignableFrom(Identifier.class)) {
                    m.invoke(null, ctx, tex, x, y, size);
                    return;
                }
            }
        } catch (Throwable ignored) {
            // Swallow — the caller has its own visual fallback.
        }
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
     *   <li>1.21.2+: {@code drawTexture(RenderLayer, Identifier, x, y, u, v, w, h, texW, texH)} (10 args)</li>
     * </ul>
     * We pick whichever overload exists on the runtime DrawContext class.
     * Failures are swallowed so callers don't have to wrap every call.
     */
    public static void drawTexture(DrawContext ctx, Identifier tex,
                                   int x, int y, float u, float v,
                                   int w, int h, int texW, int texH) {
        if (ctx == null || tex == null) return;
        try {
            for (Method m : DrawContext.class.getMethods()) {
                if (!"drawTexture".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                // 9-arg variant on 1.21.1 — first param is Identifier
                if (p.length == 9 && p[0] == Identifier.class) {
                    m.invoke(ctx, tex, x, y, u, v, w, h, texW, texH);
                    return;
                }
                // 10-arg variant on 1.21.2+ — first param is a RenderLayer
                // factory (Function<Identifier, RenderLayer>); pass null so
                // Vanilla uses its default getGuiTextured(Identifier).
                if (p.length == 10 && p[1] == Identifier.class) {
                    Object renderLayer = defaultGuiRenderLayer(p[0], tex);
                    m.invoke(ctx, renderLayer, tex, x, y, u, v, w, h, texW, texH);
                    return;
                }
            }
        } catch (Throwable ignored) {
            // Visual fallback handled by caller.
        }
    }

    private static Object defaultGuiRenderLayer(Class<?> firstParamType, Identifier tex) {
        // 1.21.2 added an explicit Function<Identifier, RenderLayer> first
        // parameter. The standard choice is RenderLayer::getGuiTextured.
        try {
            Class<?> renderLayerCls = Class.forName("net.minecraft.client.render.RenderLayer");
            Method factory = renderLayerCls.getMethod("getGuiTextured", Identifier.class);
            // If the parameter type is Function-like, return a method ref;
            // if it's already RenderLayer, return the resolved instance.
            if (firstParamType.isAssignableFrom(renderLayerCls)) {
                return factory.invoke(null, tex);
            }
            // Function<Identifier, RenderLayer> — wrap the factory.
            if (java.util.function.Function.class.isAssignableFrom(firstParamType)) {
                return (java.util.function.Function<Identifier, Object>) (id -> {
                    try { return factory.invoke(null, id); }
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

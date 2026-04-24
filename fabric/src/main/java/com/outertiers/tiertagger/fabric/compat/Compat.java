package com.outertiers.tiertagger.fabric.compat;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.lang.reflect.Method;
import java.util.Optional;

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
}

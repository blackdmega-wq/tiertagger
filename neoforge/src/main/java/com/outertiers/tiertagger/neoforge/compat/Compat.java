package com.outertiers.tiertagger.neoforge.compat;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * NeoForge twin of the Fabric {@code Compat} shim. Papers over the same two
 * Vanilla API breakages between MC 1.21.1 and 1.21.2+ so a single source
 * compiles against every 1.21.x Mojmap.
 *
 * <ul>
 *   <li>{@code PlayerFaceRenderer.draw(...)} — accepts
 *       {@link ResourceLocation} on 1.21.1, {@link PlayerSkin} on
 *       1.21.2+.</li>
 *   <li>{@code BuiltInRegistries.ITEM.get(ResourceLocation)} — returns
 *       {@link Item} on 1.21.1, {@code Optional<Holder.Reference<Item>>}
 *       on 1.21.2+.</li>
 * </ul>
 */
public final class Compat {
    private Compat() {}

    public static void drawPlayerFace(GuiGraphics ctx, PlayerSkin skin,
                                      ResourceLocation fallback, int x, int y, int size) {
        ResourceLocation tex = (skin != null && skin.texture() != null) ? skin.texture() : fallback;
        try {
            for (Method m : PlayerFaceRenderer.class.getDeclaredMethods()) {
                if (!"draw".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 5) continue;
                if (skin != null && p[1].isAssignableFrom(PlayerSkin.class)) {
                    m.invoke(null, ctx, skin, x, y, size);
                    return;
                }
                if (tex != null && p[1].isAssignableFrom(ResourceLocation.class)) {
                    m.invoke(null, ctx, tex, x, y, size);
                    return;
                }
            }
        } catch (Throwable ignored) {
            // Caller has its own visual fallback.
        }
    }

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
}

package com.outertiers.tiertagger.fabric.mixin;

import com.outertiers.tiertagger.common.MojangResolver;
import com.outertiers.tiertagger.common.PlayerData;
import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.fabric.BadgeRenderer;
import com.outertiers.tiertagger.fabric.compat.Profiles;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Nametag badge injector for the EntityRenderState-based render pipeline
 * introduced in Minecraft 1.21.5. The 1.21.1 mixin
 * ({@link PlayerEntityRendererMixin}) targets the old method signature
 * which no longer exists on 1.21.5+.
 *
 * <p>In the new pipeline, {@code Text} is no longer a method parameter; it
 * lives on {@code EntityRenderState.displayName} (a public field). We
 * inject at HEAD of {@code renderLabelIfPresent}, look up the player by
 * name from the client's tab list to recover the UUID, build the badge
 * label, and overwrite {@code state.displayName} in place. Vanilla then
 * renders our prefixed text instead of the bare name.
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerNametagMixin1215 {

    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    @Inject(
        method = "renderLabelIfPresent",
        at = @At("HEAD"),
        require = 0
    )
    private void tiertagger$prependBadge(PlayerEntityRenderState state,
                                          Object matrices,
                                          Object queue,
                                          Object camera,
                                          CallbackInfo ci) {
        try {
            TierConfig cfg = TierTaggerCore.config();
            if (cfg == null || !cfg.showNametag) return;
            if (state == null) return;

            Text original = state.displayName;
            if (original == null) return;

            String name = original.getString();
            if (name == null || name.isBlank()) return;

            // Try to recover the player's UUID from the tab list so the
            // tier cache can be primed with a stable Mojang id; otherwise
            // we fall back to name-keyed lookups (still works).
            try {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc != null && mc.getNetworkHandler() != null) {
                    PlayerListEntry e = mc.getNetworkHandler().getPlayerListEntry(name);
                    if (e != null && e.getProfile() != null) {
                        String hex = Profiles.idHex(e.getProfile());
                        if (hex != null) MojangResolver.cache(name, hex);
                    }
                }
            } catch (Throwable ignored) {}

            Optional<PlayerData> opt = TierTaggerCore.cache().peekData(name);
            if (opt.isEmpty()) return;

            MutableText wrapped = BadgeRenderer.wrapNametag(cfg, opt.get(), original);
            if (wrapped == null) return;
            state.displayName = wrapped;
        } catch (Throwable t) {
            if (WARNED.compareAndSet(false, true)) {
                TierTaggerCore.LOGGER.warn(
                    "[TierTagger] 1.21.5+ nametag mixin failed (further errors suppressed)", t);
            } else {
                TierTaggerCore.LOGGER.debug("[TierTagger] 1.21.5+ nametag mixin failed: {}", t.toString());
            }
        }
    }
}

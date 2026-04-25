package com.outertiers.tiertagger.fabric.mixin;

import com.outertiers.tiertagger.common.PlayerData;
import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.fabric.BadgeRenderer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.text.MutableText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wraps the player nametag (visible in F5 / when looking at other players) with
 *   [LEFT BADGE] &lt;name&gt; [RIGHT BADGE]
 *
 * v1.21.11.11 fix — BLACK SCREEN ON LAUNCH:
 * Earlier versions (1.21.11.10 and below) declared three @Inject variants
 * targeting {@code renderLabelIfPresent*} with {@code Object}-typed
 * render-pipeline parameters as a "fits any MC version" hack. This bug was
 * already documented in v1.7.7 of the README: Mixin's descriptor validator
 * rejects mismatching parameter types BEFORE it even consults the
 * {@code require = 0} flag, so the entire mod fails to load and the player
 * sees a permanent black screen on launch with no clear error in chat.
 *
 * This rewrite uses a single, properly-typed @Inject into the much more
 * stable {@code updateRenderState(AbstractClientPlayerEntity,
 * PlayerEntityRenderState, float)} method on PlayerEntityRenderer. That
 * signature has been stable since the EntityRenderState refactor in MC
 * 1.21.5 and is what every per-MC-version jar actually targets. The
 * functionality is identical: we still mutate {@code state.playerName} (and
 * {@code state.displayName} when present) so the badge is rendered above
 * the player's head exactly as before.
 *
 * {@code require = 0} is kept as a belt-and-braces safety net — if a future
 * MC version renames or refactors {@code updateRenderState}, the mod
 * silently no-ops the nametag feature instead of crashing the client.
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {

    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    /**
     * Inject at TAIL of {@code updateRenderState} so {@code state.playerName}
     * has already been populated by vanilla before we wrap it. Explicit
     * descriptor in {@code method = ...} so Mixin matches by exact signature
     * instead of trying to infer one from the inject method's parameter types
     * (which is what produced the InvalidInjectionException black-screen
     * crash in 1.21.11.10).
     */
    @Inject(
        method = "updateRenderState(Lnet/minecraft/client/network/AbstractClientPlayerEntity;Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;F)V",
        at = @At("TAIL"),
        require = 0
    )
    private void tiertagger$wrapNametag(AbstractClientPlayerEntity player,
                                        PlayerEntityRenderState state,
                                        float tickDelta,
                                        CallbackInfo ci) {
        applyNametag(state);
    }

    private static void applyNametag(PlayerEntityRenderState state) {
        try {
            TierConfig cfg = TierTaggerCore.config();
            if (cfg == null || !cfg.showNametag) return;
            if (state == null || state.playerName == null) return;

            String name = state.playerName.getString();
            if (name == null || name.isBlank()) return;

            Optional<PlayerData> opt = TierTaggerCore.cache().peekData(name);
            if (opt.isEmpty()) return;

            MutableText wrapped = BadgeRenderer.wrapNametag(cfg, opt.get(), state.playerName);
            if (wrapped == null) return;

            state.playerName = wrapped;
            // displayName is the field actually consumed by renderLabelIfPresent
            // in 1.21.5+ — keep it in sync. Wrapped in try/catch because the
            // field name has historically drifted across snapshots.
            try { state.displayName = wrapped; } catch (Throwable ignored) {}
        } catch (Throwable t) {
            if (WARNED.compareAndSet(false, true)) {
                TierTaggerCore.LOGGER.warn("[TierTagger] nametag mixin failed (further errors suppressed)", t);
            } else {
                TierTaggerCore.LOGGER.debug("[TierTagger] nametag mixin failed: {}", t.toString());
            }
        }
    }
}

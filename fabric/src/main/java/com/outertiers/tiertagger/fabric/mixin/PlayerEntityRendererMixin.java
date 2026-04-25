package com.outertiers.tiertagger.fabric.mixin;

import com.outertiers.tiertagger.common.PlayerData;
import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.fabric.BadgeRenderer;
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
 * Wraps the player nametag (visible in F5 / when looking at other players) with
 *   [LEFT BADGE] <name> [RIGHT BADGE]
 *
 * Three @Inject variants (3-param, 4-param, 5-param after the state) cover all
 * known renderLabelIfPresent signatures across MC 1.21.1–1.21.11. Exactly one
 * variant will match at runtime; the others are silently skipped (require = 0).
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {

    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    // ── 3-param variant (some 1.21.x builds) ─────────────────────────────────
    @Inject(method = "renderLabelIfPresent*", at = @At("HEAD"), require = 0)
    private void tiertagger$wrapNametag3(PlayerEntityRenderState state,
                                         Object b, Object c,
                                         CallbackInfo ci) {
        applyNametag(state);
    }

    // ── 4-param variant ───────────────────────────────────────────────────────
    @Inject(method = "renderLabelIfPresent*", at = @At("HEAD"), require = 0)
    private void tiertagger$wrapNametag4(PlayerEntityRenderState state,
                                         Object b, Object c, Object d,
                                         CallbackInfo ci) {
        applyNametag(state);
    }

    // ── 5-param variant (1.21.11+ added CameraRenderState or similar) ─────────
    @Inject(method = "renderLabelIfPresent*", at = @At("HEAD"), require = 0)
    private void tiertagger$wrapNametag5(PlayerEntityRenderState state,
                                         Object b, Object c, Object d, Object e,
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


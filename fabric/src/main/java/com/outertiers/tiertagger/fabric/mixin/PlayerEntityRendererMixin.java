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
 * MC 1.21.11 ships a render-state pipeline where the label text is read from
 * the {@link PlayerEntityRenderState#playerName} (and the parent
 * {@code EntityRenderState.displayName}) fields rather than passed in as a
 * method argument. {@code renderLabelIfPresent} now has the signature
 *
 *   renderLabelIfPresent(PlayerEntityRenderState state,
 *                        MatrixStack matrices,
 *                        OrderedRenderCommandQueue queue,
 *                        CameraRenderState camera)
 *
 * so we can't intercept the {@code Text} arg the way the old mod did. Instead
 * we mutate the state's name fields in-place at HEAD and the renderer happily
 * picks our wrapped text up.
 *
 * The injection is {@code require = 0} so the mod still loads if Mojang
 * ever changes the descriptor again — F5 badges will just stop appearing
 * until the mod is updated.
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {

    /** Logged at WARN once per session so a real bug is actually visible in latest.log. */
    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    @Inject(method = "renderLabelIfPresent*", at = @At("HEAD"), require = 0)
    private void tiertagger$wrapNametag(PlayerEntityRenderState state,
                                        Object matrices,
                                        Object queue,
                                        Object camera,
                                        CallbackInfo ci) {
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

            // Mutate both the player-specific and the inherited display name
            // so anything in the renderer that reads from either still sees
            // the wrapped version.
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

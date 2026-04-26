package com.outertiers.tiertagger.fabric.mixin;

import com.outertiers.tiertagger.common.PlayerData;
import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.fabric.BadgeRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Backup nametag wrapper attached to {@link LivingEntityRenderer}.
 *
 * <p><b>Why a backup mixin exists:</b> the primary
 * {@link PlayerEntityRendererMixin} targets
 * {@code PlayerRenderer.updateRenderState(AbstractClientPlayer,
 * PlayerRenderState, float)}. That method is the player-renderer override
 * — but if Mojang ever inlines or removes the override on a future MC version,
 * our specific @Inject silently no-ops (require = 0) and the player nametag
 * wrapping disappears with no warning to the user.
 *
 * <p>This file also targets {@code LivingEntityRenderer.updateRenderState} —
 * a method that <em>has</em> to exist for the entity-render system to work at
 * all — and filters at runtime to {@link PlayerRenderState}. If both
 * mixins apply they double-write the field, which is a harmless no-op (the
 * second write produces the same wrapped {@link Text}). If only one applies,
 * the user still gets badges above heads.
 *
 * <p>Implementation note — call ordering:
 * <pre>
 *   PlayerRenderer.updateRenderState(...)      // (1) entry
 *     super.updateRenderState(...)                   // (2) → LivingEntityRenderer
 *       super.updateRenderState(...)                 // (3) → EntityRenderer (sets state.displayName)
 *     [LivingEntity-specific updates]
 *     [TAIL of LivingEntityRenderer.updateRenderState]   ← THIS BACKUP MIXIN FIRES HERE
 *   [PlayerRenderer-specific updates: state.playerName, capeY, …]
 *   [TAIL of PlayerRenderer.updateRenderState]     ← PRIMARY MIXIN FIRES HERE
 * </pre>
 *
 * <p>So this backup runs BEFORE {@code state.playerName} is populated by
 * {@code PlayerRenderer}. We therefore extract the username from the
 * {@link AbstractClientPlayer#getGameProfile()} on the entity itself.
 * {@code state.displayName} has already been populated by
 * {@code EntityRenderer.updateRenderState} at this point, so wrapping it here
 * is safe and the wrap survives the rest of the render-state update.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {

    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    @Inject(
        method = "updateRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
        at = @At("TAIL"),
        require = 0
    )
    private void tiertagger$wrapPlayerNametag(LivingEntity entity,
                                              LivingEntityRenderState state,
                                              float tickDelta,
                                              CallbackInfo ci) {
        try {
            if (!(entity instanceof AbstractClientPlayer player)) return;
            if (!(state instanceof PlayerRenderState ps)) return;

            TierConfig cfg = TierTaggerCore.config();
            if (cfg == null || !cfg.showNametag) return;

            // Use getNameForScoreboard() — stable since MC 1.20, returns the raw
            // username string. Avoids the GameProfile API drift between
            // versions of com.mojang.authlib (which became a Java record in
            // recent releases and dropped the legacy getName() accessor).
            String name = null;
            try { name = player.getNameForScoreboard(); } catch (Throwable ignored) {}
            if (name == null || name.isBlank()) {
                // Last-ditch fallback: derive the username from the entity's
                // Text name (works on every MC version that has this mixin).
                try { name = entity.getName().getString(); } catch (Throwable ignored) {}
            }
            if (name == null || name.isBlank()) return;

            Optional<PlayerData> opt = TierTaggerCore.cache().peekData(name);
            if (opt.isEmpty()) return;

            // Wrap whichever original we can find: prefer displayName (the field
            // that vanilla actually renders via renderLabelIfPresent), fall back
            // to playerName (might be null at this point) or just the username.
            Component base = null;
            try { base = ps.displayName; } catch (Throwable ignored) {}
            if (base == null) {
                try { base = ps.playerName; } catch (Throwable ignored) {}
            }
            if (base == null) base = Component.literal(name);

            MutableComponent wrapped = BadgeRenderer.wrapNametag(cfg, opt.get(), base);
            if (wrapped == null) return;

            try { ps.displayName = wrapped; } catch (Throwable ignored) {}
            try { ps.playerName  = wrapped; } catch (Throwable ignored) {}
        } catch (Throwable t) {
            if (WARNED.compareAndSet(false, true)) {
                TierTaggerCore.LOGGER.warn("[TierTagger] LivingEntityRenderer backup nametag mixin failed (further errors suppressed)", t);
            } else {
                TierTaggerCore.LOGGER.debug("[TierTagger] LivingEntityRenderer backup nametag mixin failed: {}", t.toString());
            }
        }
    }
}

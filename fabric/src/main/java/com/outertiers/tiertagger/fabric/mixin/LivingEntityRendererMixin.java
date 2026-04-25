package com.outertiers.tiertagger.fabric.mixin;

import com.outertiers.tiertagger.common.PlayerData;
import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.fabric.BadgeRenderer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
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
 * {@code PlayerEntityRenderer.updateRenderState(AbstractClientPlayerEntity,
 * PlayerEntityRenderState, float)}. That method is the player-renderer override
 * — but if Mojang ever inlines or removes the override on a future MC version,
 * our specific @Inject silently no-ops (require = 0) and the player nametag
 * wrapping disappears with no warning to the user.
 *
 * <p>This file also targets {@code LivingEntityRenderer.updateRenderState} —
 * a method that <em>has</em> to exist for the entity-render system to work at
 * all — and filters at runtime to {@link PlayerEntityRenderState}. If both
 * mixins apply they double-write the field, which is a harmless no-op (the
 * second write produces the same wrapped {@link Text}). If only one applies,
 * the user still gets badges above heads.
 *
 * <p>Implementation note — call ordering:
 * <pre>
 *   PlayerEntityRenderer.updateRenderState(...)      // (1) entry
 *     super.updateRenderState(...)                   // (2) → LivingEntityRenderer
 *       super.updateRenderState(...)                 // (3) → EntityRenderer (sets state.displayName)
 *     [LivingEntity-specific updates]
 *     [TAIL of LivingEntityRenderer.updateRenderState]   ← THIS BACKUP MIXIN FIRES HERE
 *   [PlayerEntityRenderer-specific updates: state.playerName, capeY, …]
 *   [TAIL of PlayerEntityRenderer.updateRenderState]     ← PRIMARY MIXIN FIRES HERE
 * </pre>
 *
 * <p>So this backup runs BEFORE {@code state.playerName} is populated by
 * {@code PlayerEntityRenderer}. We therefore extract the username from the
 * {@link AbstractClientPlayerEntity#getGameProfile()} on the entity itself.
 * {@code state.displayName} has already been populated by
 * {@code EntityRenderer.updateRenderState} at this point, so wrapping it here
 * is safe and the wrap survives the rest of the render-state update.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {

    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    @Inject(
        method = "updateRenderState(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;F)V",
        at = @At("TAIL"),
        require = 0
    )
    private void tiertagger$wrapPlayerNametag(LivingEntity entity,
                                              LivingEntityRenderState state,
                                              float tickDelta,
                                              CallbackInfo ci) {
        try {
            if (!(entity instanceof AbstractClientPlayerEntity player)) return;
            if (!(state instanceof PlayerEntityRenderState ps)) return;

            TierConfig cfg = TierTaggerCore.config();
            if (cfg == null || !cfg.showNametag) return;

            String name;
            try {
                name = player.getGameProfile().getName();
            } catch (Throwable t) {
                return;
            }
            if (name == null || name.isBlank()) return;

            Optional<PlayerData> opt = TierTaggerCore.cache().peekData(name);
            if (opt.isEmpty()) return;

            // Wrap whichever original we can find: prefer displayName (the field
            // that vanilla actually renders via renderLabelIfPresent), fall back
            // to playerName (might be null at this point) or just the username.
            Text base = null;
            try { base = ps.displayName; } catch (Throwable ignored) {}
            if (base == null) {
                try { base = ps.playerName; } catch (Throwable ignored) {}
            }
            if (base == null) base = Text.literal(name);

            MutableText wrapped = BadgeRenderer.wrapNametag(cfg, opt.get(), base);
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

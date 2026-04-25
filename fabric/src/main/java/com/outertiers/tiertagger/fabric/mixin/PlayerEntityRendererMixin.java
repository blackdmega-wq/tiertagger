package com.outertiers.tiertagger.fabric.mixin;

import com.outertiers.tiertagger.common.MojangResolver;
import com.outertiers.tiertagger.common.PlayerData;
import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.fabric.BadgeRenderer;
import com.outertiers.tiertagger.fabric.compat.Profiles;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {

    /** Logged at WARN once per session so a real bug is actually visible in latest.log. */
    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    @ModifyVariable(
        method = "renderLabelIfPresent(Lnet/minecraft/client/network/AbstractClientPlayerEntity;Lnet/minecraft/text/Text;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;IF)V",
        at = @At("HEAD"),
        argsOnly = true,
        require = 0
    )
    private Text tiertagger$prependBadge(Text original,
                                         AbstractClientPlayerEntity entity,
                                         Text text,
                                         MatrixStack matrices,
                                         VertexConsumerProvider vcp,
                                         int light,
                                         float tickDelta) {
        try {
            TierConfig cfg = TierTaggerCore.config();
            if (cfg == null || !cfg.showNametag) return original;
            if (entity == null || entity.getGameProfile() == null) return original;
            String name = Profiles.name(entity.getGameProfile());
            if (name == null || name.isBlank()) return original;
            try {
                String hex = Profiles.idHex(entity.getGameProfile());
                if (hex != null) MojangResolver.cache(name, hex);
            } catch (Throwable ignored) {}

            Optional<PlayerData> opt = TierTaggerCore.cache().peekData(name);
            if (opt.isEmpty()) return original;
            MutableText wrapped = BadgeRenderer.wrapNametag(cfg, opt.get(), original);
            return wrapped == null ? original : wrapped;
        } catch (Throwable t) {
            // First failure: log full stack trace at WARN so users can see what
            // actually broke. Subsequent failures stay at DEBUG to avoid log spam.
            if (WARNED.compareAndSet(false, true)) {
                TierTaggerCore.LOGGER.warn("[TierTagger] nametag mixin failed (further errors suppressed)", t);
            } else {
                TierTaggerCore.LOGGER.debug("[TierTagger] nametag mixin failed: {}", t.toString());
            }
            return original;
        }
    }
}

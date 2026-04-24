package com.outertiers.tiertagger.fabric.mixin;

import com.outertiers.tiertagger.common.MojangResolver;
import com.outertiers.tiertagger.common.PlayerData;
import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.fabric.BadgeRenderer;
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

@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {

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
            String name = entity.getGameProfile().getName();
            if (name == null || name.isBlank()) return original;
            try {
                if (entity.getGameProfile().getId() != null) {
                    MojangResolver.cache(name, entity.getGameProfile().getId().toString().replace("-", ""));
                }
            } catch (Throwable ignored) {}

            Optional<PlayerData> opt = TierTaggerCore.cache().peekData(name);
            if (opt.isEmpty()) return original;
            MutableText wrapped = BadgeRenderer.wrapNametag(cfg, opt.get(), original);
            return wrapped == null ? original : wrapped;
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.debug("[TierTagger] nametag mixin failed: {}", t.toString());
            return original;
        }
    }
}

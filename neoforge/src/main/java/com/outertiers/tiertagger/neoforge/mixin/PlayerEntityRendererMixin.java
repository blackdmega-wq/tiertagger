package com.outertiers.tiertagger.neoforge.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.outertiers.tiertagger.common.MojangResolver;
import com.outertiers.tiertagger.common.PlayerData;
import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.neoforge.BadgeRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Optional;

@Mixin(PlayerRenderer.class)
public abstract class PlayerEntityRendererMixin {

    @ModifyVariable(
        method = "renderNameTag(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/network/chat/Component;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V",
        at = @At("HEAD"),
        argsOnly = true,
        require = 0
    )
    private Component tiertagger$prependBadge(Component original,
                                              AbstractClientPlayer entity,
                                              Component component,
                                              PoseStack pose,
                                              MultiBufferSource buffer,
                                              int packedLight,
                                              float partialTick) {
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
            MutableComponent wrapped = BadgeRenderer.wrapNametag(cfg, opt.get(), original);
            return wrapped == null ? original : wrapped;
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.debug("[TierTagger] nametag mixin failed: {}", t.toString());
            return original;
        }
    }
}

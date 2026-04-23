package com.outertiers.tiertagger.neoforge.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.outertiers.tiertagger.common.TierCache;
import com.outertiers.tiertagger.common.TierTaggerCore;
import net.minecraft.ChatFormatting;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Optional;

/**
 * Prepends the tier badge in front of the player nametag rendered above the head.
 * Uses {@link ModifyVariable} on the {@code Component} parameter of {@code renderNameTag}.
 */
@Mixin(PlayerRenderer.class)
public abstract class PlayerEntityRendererMixin {

    @ModifyVariable(
        method = "renderNameTag(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/network/chat/Component;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private Component tiertagger$prependBadge(Component original,
                                              AbstractClientPlayer entity,
                                              Component component,
                                              PoseStack pose,
                                              MultiBufferSource buffer,
                                              int packedLight,
                                              float partialTick) {
        if (TierTaggerCore.config() == null || !TierTaggerCore.config().showNametag) return original;
        if (entity == null || entity.getGameProfile() == null) return original;
        String name = entity.getGameProfile().getName();
        if (name == null || name.isBlank()) return original;

        Optional<TierCache.Entry> opt = TierTaggerCore.cache().peek(name);
        if (opt.isEmpty()) return original;
        String tier = TierTaggerCore.chooseTier(opt.get());
        if (tier == null || tier.isBlank()) return original;

        ChatFormatting colour = ChatFormatting.getByCode(TierTaggerCore.colourCodeFor(tier));
        if (colour == null) colour = ChatFormatting.GRAY;

        MutableComponent badge = Component.literal("[")
                .append(Component.literal(tier).withStyle(colour, ChatFormatting.BOLD))
                .append(Component.literal("] "))
                .withStyle(ChatFormatting.GRAY);

        return Component.empty().append(badge).append(original == null ? Component.empty() : original);
    }
}

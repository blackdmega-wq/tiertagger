package com.outertiers.tiertagger.fabric.mixin;

import com.outertiers.tiertagger.common.TierCache;
import com.outertiers.tiertagger.common.TierTaggerCore;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Optional;

/**
 * Prepends the tier badge in front of the player nametag rendered above the head.
 * Targets the {@code Text} parameter of {@code renderLabelIfPresent} on the player renderer
 * via {@link ModifyVariable} so we don't have to reimplement vanilla rendering.
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {

    @ModifyVariable(
        method = "renderLabelIfPresent(Lnet/minecraft/client/network/AbstractClientPlayerEntity;Lnet/minecraft/text/Text;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;IF)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private Text tiertagger$prependBadge(Text original,
                                         AbstractClientPlayerEntity entity,
                                         Text text,
                                         MatrixStack matrices,
                                         VertexConsumerProvider vcp,
                                         int light,
                                         float tickDelta) {
        if (TierTaggerCore.config() == null || !TierTaggerCore.config().showNametag) return original;
        if (entity == null || entity.getGameProfile() == null) return original;
        String name = entity.getGameProfile().getName();
        if (name == null || name.isBlank()) return original;

        Optional<TierCache.Entry> opt = TierTaggerCore.cache().peek(name);
        if (opt.isEmpty()) return original;
        String tier = TierTaggerCore.chooseTier(opt.get());
        if (tier == null || tier.isBlank()) return original;

        Formatting colour = Formatting.byCode(TierTaggerCore.colourCodeFor(tier));
        if (colour == null) colour = Formatting.GRAY;

        MutableText badge = Text.literal("[")
                .append(Text.literal(tier).formatted(colour, Formatting.BOLD))
                .append(Text.literal("] "))
                .formatted(Formatting.GRAY);

        return Text.empty().append(badge).append(original == null ? Text.empty() : original);
    }
}

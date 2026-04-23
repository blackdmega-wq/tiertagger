package com.outertiers.tiertagger.neoforge.mixin;

import com.outertiers.tiertagger.common.TierCache;
import com.outertiers.tiertagger.common.TierFormat;
import com.outertiers.tiertagger.common.TierTaggerCore;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(PlayerTabOverlay.class)
public class PlayerListHudMixin {

    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
    private void tiertagger$appendTier(PlayerInfo entry, CallbackInfoReturnable<Component> cir) {
        try {
            if (TierTaggerCore.config() == null || !TierTaggerCore.config().showInTab) return;
            if (entry == null || entry.getProfile() == null) return;
            String name = entry.getProfile().getName();
            if (name == null || name.isBlank()) return;

            Optional<TierCache.Entry> opt = TierTaggerCore.cache().peek(name);
            if (opt.isEmpty()) return;
            String tier = TierTaggerCore.chooseTier(opt.get());
            if (tier == null || tier.isBlank()) return;

            ChatFormatting colour = TierFormat.colored()
                ? ChatFormatting.getByCode(TierTaggerCore.colourCodeFor(tier))
                : ChatFormatting.GRAY;
            if (colour == null) colour = ChatFormatting.GRAY;

            String label = TierFormat.label(tier);
            MutableComponent badge;
            if (TierFormat.useBrackets()) {
                badge = Component.literal(" [")
                        .append(Component.literal(label).withStyle(colour, ChatFormatting.BOLD))
                        .append(Component.literal("]"))
                        .withStyle(ChatFormatting.GRAY);
            } else {
                badge = Component.literal(" ")
                        .append(Component.literal(label).withStyle(colour, ChatFormatting.BOLD));
            }

            Component original = cir.getReturnValue();
            MutableComponent combined = (original == null ? Component.empty() : original.copy()).append(badge);
            cir.setReturnValue(combined);
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.debug("[TierTagger] tab badge mixin failed: {}", t.toString());
        }
    }
}

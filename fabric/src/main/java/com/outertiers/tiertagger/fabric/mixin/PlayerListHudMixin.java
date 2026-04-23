package com.outertiers.tiertagger.fabric.mixin;

import com.outertiers.tiertagger.common.TierCache;
import com.outertiers.tiertagger.common.TierFormat;
import com.outertiers.tiertagger.common.TierTaggerCore;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(PlayerListHud.class)
public class PlayerListHudMixin {

    @Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true)
    private void tiertagger$appendTier(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
        try {
            if (TierTaggerCore.config() == null || !TierTaggerCore.config().showInTab) return;
            if (entry == null || entry.getProfile() == null) return;
            String name = entry.getProfile().getName();
            if (name == null || name.isBlank()) return;

            Optional<TierCache.Entry> opt = TierTaggerCore.cache().peek(name);
            if (opt.isEmpty()) return;
            String tier = TierTaggerCore.chooseTier(opt.get());
            if (tier == null || tier.isBlank()) return;

            Formatting colour = TierFormat.colored()
                ? Formatting.byCode(TierTaggerCore.colourCodeFor(tier))
                : Formatting.GRAY;
            if (colour == null) colour = Formatting.GRAY;

            String label = TierFormat.label(tier);
            MutableText badge;
            if (TierFormat.useBrackets()) {
                badge = Text.literal(" [")
                        .append(Text.literal(label).formatted(colour, Formatting.BOLD))
                        .append(Text.literal("]"))
                        .formatted(Formatting.GRAY);
            } else {
                badge = Text.literal(" ")
                        .append(Text.literal(label).formatted(colour, Formatting.BOLD));
            }

            Text original = cir.getReturnValue();
            MutableText combined = (original == null ? Text.empty() : original.copy()).append(badge);
            cir.setReturnValue(combined);
        } catch (Throwable t) {
            // Never crash the game from this mixin — TierTagger is purely cosmetic.
            TierTaggerCore.LOGGER.debug("[TierTagger] tab badge mixin failed: {}", t.toString());
        }
    }
}

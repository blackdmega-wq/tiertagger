package com.outertiers.tiertagger.fabric.mixin;

import com.outertiers.tiertagger.common.MojangResolver;
import com.outertiers.tiertagger.common.PlayerData;
import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.fabric.BadgeRenderer;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
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
            TierConfig cfg = TierTaggerCore.config();
            if (cfg == null || !cfg.showInTab) return;
            if (entry == null || entry.getProfile() == null) return;
            String name = entry.getProfile().getName();
            if (name == null || name.isBlank()) return;
            try {
                if (entry.getProfile().getId() != null) {
                    MojangResolver.cache(name, entry.getProfile().getId().toString().replace("-", ""));
                }
            } catch (Throwable ignored) {}

            Optional<PlayerData> opt = TierTaggerCore.cache().peekData(name);
            if (opt.isEmpty()) return;
            MutableText badges = BadgeRenderer.buildTabSuffix(cfg, opt.get());
            if (badges == null) return;

            Text original = cir.getReturnValue();
            cir.setReturnValue((original == null ? Text.empty() : original.copy()).append(badges));
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.debug("[TierTagger] tab badge mixin failed: {}", t.toString());
        }
    }
}

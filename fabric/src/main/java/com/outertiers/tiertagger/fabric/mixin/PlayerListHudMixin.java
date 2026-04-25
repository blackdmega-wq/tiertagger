package com.outertiers.tiertagger.fabric.mixin;

import com.outertiers.tiertagger.common.MojangResolver;
import com.outertiers.tiertagger.common.PlayerData;
import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.fabric.BadgeRenderer;
import com.outertiers.tiertagger.fabric.compat.Profiles;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Mixin(PlayerListHud.class)
public class PlayerListHudMixin {

    /** Logged at WARN once per session so a real bug is actually visible in latest.log. */
    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    /**
     * require = 0: if the {@code getPlayerName(PlayerListEntry)} signature ever
     * shifts in a future MC version, the mixin silently no-ops instead of
     * killing the client at apply time. Without this, the user sees a black
     * screen with no useful error message instead of just "tab badges missing".
     */
    @Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true, require = 0)
    private void tiertagger$appendTier(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
        try {
            TierConfig cfg = TierTaggerCore.config();
            if (cfg == null || !cfg.showInTab) return;
            if (entry == null || entry.getProfile() == null) return;
            String name = Profiles.name(entry.getProfile());
            if (name == null || name.isBlank()) return;
            try {
                String hex = Profiles.idHex(entry.getProfile());
                if (hex != null) MojangResolver.cache(name, hex);
            } catch (Throwable ignored) {}

            Optional<PlayerData> opt = TierTaggerCore.cache().peekData(name);
            if (opt.isEmpty()) return;
            MutableText badges = BadgeRenderer.buildTabSuffix(cfg, opt.get());
            if (badges == null) return;

            Text original = cir.getReturnValue();
            cir.setReturnValue((original == null ? Text.empty() : original.copy()).append(badges));
        } catch (Throwable t) {
            // The first time we hit a render error, log the full stack trace at WARN
            // so users can actually see what's wrong. Subsequent errors stay at DEBUG
            // so the log isn't spammed every tick.
            if (WARNED.compareAndSet(false, true)) {
                TierTaggerCore.LOGGER.warn("[TierTagger] tab badge mixin failed (further errors suppressed)", t);
            } else {
                TierTaggerCore.LOGGER.debug("[TierTagger] tab badge mixin failed: {}", t.toString());
            }
        }
    }
}

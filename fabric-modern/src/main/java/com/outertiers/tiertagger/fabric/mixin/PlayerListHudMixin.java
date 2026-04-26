package com.outertiers.tiertagger.fabric.mixin;

import com.outertiers.tiertagger.common.MojangResolver;
import com.outertiers.tiertagger.common.PlayerData;
import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.fabric.BadgeRenderer;
import com.outertiers.tiertagger.fabric.compat.Profiles;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wraps the player's tab-list display name with:
 *   [LEFT BADGE] <name> [RIGHT BADGE]
 *
 * Each badge contains the gamemode icon (rendered via the {@code tiertagger:icons}
 * font) plus the tier label coloured by tier rank.
 *
 * v1.21.11 fix: previously both badges were appended to the right of the
 * name. Now we prepend the left one so the badges actually sit on opposite
 * sides like the user asked.
 */
@Mixin(PlayerTabOverlay.class)
public class PlayerListHudMixin {

    /** Logged at WARN once per session so a real bug is actually visible in latest.log. */
    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    /**
     * require = 0: if the {@code getPlayerName(PlayerInfo)} signature ever
     * shifts in a future MC version, the mixin silently no-ops instead of
     * killing the client at apply time.
     */
    @Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true, require = 0)
    private void tiertagger$wrapTabName(PlayerInfo entry, CallbackInfoReturnable<Component> cir) {
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

            MutableComponent prefix = BadgeRenderer.buildTabPrefix(cfg, opt.get());
            MutableComponent suffix = BadgeRenderer.buildTabSuffix(cfg, opt.get());
            if (prefix == null && suffix == null) return;

            Component original = cir.getReturnValue();
            MutableComponent out = Component.empty();
            if (prefix != null) out.append(prefix);
            out.append(original == null ? Component.empty() : original.copy());
            if (suffix != null) out.append(suffix);
            cir.setReturnValue(out);
        } catch (Throwable t) {
            if (WARNED.compareAndSet(false, true)) {
                TierTaggerCore.LOGGER.warn("[TierTagger] tab badge mixin failed (further errors suppressed)", t);
            } else {
                TierTaggerCore.LOGGER.debug("[TierTagger] tab badge mixin failed: {}", t.toString());
            }
        }
    }
}

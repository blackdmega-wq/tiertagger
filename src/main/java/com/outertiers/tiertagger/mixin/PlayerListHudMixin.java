package com.outertiers.tiertagger.mixin;

import com.outertiers.tiertagger.TierCache;
import com.outertiers.tiertagger.TierTagger;
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
        if (!TierTagger.config().showInTab) return;
        if (entry == null || entry.getProfile() == null) return;
        String name = entry.getProfile().getName();
        if (name == null || name.isBlank()) return;

        Optional<TierCache.Entry> opt = TierTagger.cache().peek(name);
        if (opt.isEmpty()) return;
        TierCache.Entry e = opt.get();
        if (e.missing) return;

        String mode = TierTagger.config().gamemode;
        String tier = "overall".equalsIgnoreCase(mode)
                ? pickHighest(e)
                : e.tiers.get(mode.toLowerCase());

        if (TierTagger.config().showPeak && e.peakTier != null && !e.peakTier.isBlank()) {
            tier = e.peakTier.toUpperCase();
        }
        if (tier == null || tier.isBlank()) return;

        Formatting colour = colourFor(tier);
        MutableText badge = Text.literal(" [")
                .append(Text.literal(tier).formatted(colour, Formatting.BOLD))
                .append(Text.literal("]"))
                .formatted(Formatting.GRAY);

        Text original = cir.getReturnValue();
        MutableText combined = (original == null ? Text.empty() : original.copy()).append(badge);
        cir.setReturnValue(combined);
    }

    private static String pickHighest(TierCache.Entry e) {
        if (e.tiers.isEmpty()) return null;
        String best = null;
        int bestScore = -1;
        for (String t : e.tiers.values()) {
            int s = score(t);
            if (s > bestScore) { bestScore = s; best = t; }
        }
        return best;
    }

    private static int score(String tier) {
        if (tier == null) return -1;
        String t = tier.toUpperCase();
        int base;
        switch (t) {
            case "HT1": return 100; case "LT1": return 90;
            case "HT2": return 80;  case "LT2": return 70;
            case "HT3": return 60;  case "LT3": return 50;
            case "HT4": return 40;  case "LT4": return 30;
            case "HT5": return 20;  case "LT5": return 10;
            default: base = 0;
        }
        return base;
    }

    private static Formatting colourFor(String tier) {
        if (tier == null) return Formatting.GRAY;
        String t = tier.toUpperCase();
        if (t.endsWith("1")) return Formatting.LIGHT_PURPLE;
        if (t.endsWith("2")) return Formatting.RED;
        if (t.endsWith("3")) return Formatting.GOLD;
        if (t.endsWith("4")) return Formatting.YELLOW;
        if (t.endsWith("5")) return Formatting.GREEN;
        return Formatting.GRAY;
    }
}

package com.outertiers.tiertagger.fabric;

import com.outertiers.tiertagger.common.PlayerData;
import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierFormat;
import com.outertiers.tiertagger.common.TierService;
import com.outertiers.tiertagger.common.TierTaggerCore;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Shared badge formatting used by both the tab list and nametag mixins. */
public final class BadgeRenderer {
    private BadgeRenderer() {}

    public static MutableText formatBadge(TierService svc, String tier, boolean serviceLabelLeading) {
        String label = TierFormat.label(tier);
        Formatting colour = TierFormat.colored()
            ? Formatting.byCode(TierTaggerCore.colourCodeFor(tier))
            : Formatting.GRAY;
        if (colour == null) colour = Formatting.GRAY;

        MutableText core;
        if (TierFormat.useBrackets()) {
            core = Text.literal("[")
                .append(Text.literal(label).formatted(colour, Formatting.BOLD))
                .append(Text.literal("]"))
                .formatted(Formatting.GRAY);
        } else {
            core = Text.literal(label).formatted(colour, Formatting.BOLD);
        }

        if (!TierFormat.showServiceLabel()) return core;

        MutableText svcLabel = Text.literal(svc.shortLabel).withColor(svc.accentArgb);
        return serviceLabelLeading
            ? svcLabel.append(Text.literal(" ")).append(core)
            : core.append(Text.literal(" ")).append(svcLabel);
    }

    /** Builds a leading-space text " [L] [R]" for the tab list, or null if no badges. */
    public static MutableText buildTabSuffix(TierConfig cfg, PlayerData data) {
        TierService leftSvc  = cfg.leftServiceEnum();
        TierService rightSvc = cfg.rightServiceEnum();
        String leftTier  = TierTaggerCore.tierForService(data, leftSvc);
        String rightTier = cfg.rightBadgeEnabled ? TierTaggerCore.tierForService(data, rightSvc) : null;
        if ((leftTier == null || leftTier.isBlank()) && (rightTier == null || rightTier.isBlank())) return null;

        MutableText out = Text.literal("");
        if (leftTier != null && !leftTier.isBlank()) {
            out.append(Text.literal(" ")).append(formatBadge(leftSvc, leftTier, true));
        }
        if (rightTier != null && !rightTier.isBlank()) {
            out.append(Text.literal(" ")).append(formatBadge(rightSvc, rightTier, false));
        }
        return out;
    }

    /** Wraps the original nametag with [LEFT] orig [RIGHT] or returns null if no badges. */
    public static MutableText wrapNametag(TierConfig cfg, PlayerData data, Text original) {
        TierService leftSvc  = cfg.leftServiceEnum();
        TierService rightSvc = cfg.rightServiceEnum();
        String leftTier  = TierTaggerCore.tierForService(data, leftSvc);
        String rightTier = cfg.rightBadgeEnabled ? TierTaggerCore.tierForService(data, rightSvc) : null;
        if ((leftTier == null || leftTier.isBlank()) && (rightTier == null || rightTier.isBlank())) return null;

        MutableText out = Text.empty();
        if (leftTier != null && !leftTier.isBlank()) {
            out.append(formatBadge(leftSvc, leftTier, true)).append(Text.literal(" "));
        }
        out.append(original == null ? Text.empty() : original);
        if (rightTier != null && !rightTier.isBlank()) {
            out.append(Text.literal(" ")).append(formatBadge(rightSvc, rightTier, false));
        }
        return out;
    }
}

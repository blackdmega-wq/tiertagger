package com.outertiers.tiertagger.neoforge;

import com.outertiers.tiertagger.common.PlayerData;
import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierFormat;
import com.outertiers.tiertagger.common.TierService;
import com.outertiers.tiertagger.common.TierTaggerCore;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Shared badge formatting used by both the tab list and nametag mixins. */
public final class BadgeRenderer {
    private BadgeRenderer() {}

    public static MutableComponent formatBadge(TierService svc, String tier, boolean serviceLabelLeading) {
        String label = TierFormat.label(tier);
        ChatFormatting colour = TierFormat.colored()
            ? ChatFormatting.getByCode(TierTaggerCore.colourCodeFor(tier))
            : ChatFormatting.GRAY;
        if (colour == null) colour = ChatFormatting.GRAY;

        MutableComponent core;
        if (TierFormat.useBrackets()) {
            core = Component.literal("[")
                .append(Component.literal(label).withStyle(colour, ChatFormatting.BOLD))
                .append(Component.literal("]"))
                .withStyle(ChatFormatting.GRAY);
        } else {
            core = Component.literal(label).withStyle(colour, ChatFormatting.BOLD);
        }

        if (!TierFormat.showServiceLabel()) return core;

        MutableComponent svcLabel = Component.literal(svc.shortLabel).withColor(svc.accentArgb);
        return serviceLabelLeading
            ? svcLabel.append(Component.literal(" ")).append(core)
            : core.append(Component.literal(" ")).append(svcLabel);
    }

    public static MutableComponent buildTabSuffix(TierConfig cfg, PlayerData data) {
        TierService leftSvc  = cfg.leftServiceEnum();
        TierService rightSvc = cfg.rightServiceEnum();
        String leftTier  = TierTaggerCore.tierForService(data, leftSvc);
        String rightTier = cfg.rightBadgeEnabled ? TierTaggerCore.tierForService(data, rightSvc) : null;
        if ((leftTier == null || leftTier.isBlank()) && (rightTier == null || rightTier.isBlank())) return null;

        MutableComponent out = Component.literal("");
        if (leftTier != null && !leftTier.isBlank()) {
            out.append(Component.literal(" ")).append(formatBadge(leftSvc, leftTier, true));
        }
        if (rightTier != null && !rightTier.isBlank()) {
            out.append(Component.literal(" ")).append(formatBadge(rightSvc, rightTier, false));
        }
        return out;
    }

    public static MutableComponent wrapNametag(TierConfig cfg, PlayerData data, Component original) {
        TierService leftSvc  = cfg.leftServiceEnum();
        TierService rightSvc = cfg.rightServiceEnum();
        String leftTier  = TierTaggerCore.tierForService(data, leftSvc);
        String rightTier = cfg.rightBadgeEnabled ? TierTaggerCore.tierForService(data, rightSvc) : null;
        if ((leftTier == null || leftTier.isBlank()) && (rightTier == null || rightTier.isBlank())) return null;

        MutableComponent out = Component.empty();
        if (leftTier != null && !leftTier.isBlank()) {
            out.append(formatBadge(leftSvc, leftTier, true)).append(Component.literal(" "));
        }
        out.append(original == null ? Component.empty() : original);
        if (rightTier != null && !rightTier.isBlank()) {
            out.append(Component.literal(" ")).append(formatBadge(rightSvc, rightTier, false));
        }
        return out;
    }
}

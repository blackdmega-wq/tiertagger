package com.outertiers.tiertagger.fabric;

import com.outertiers.tiertagger.common.ModeGlyphs;
import com.outertiers.tiertagger.common.PlayerData;
import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierFormat;
import com.outertiers.tiertagger.common.TierService;
import com.outertiers.tiertagger.common.TierTaggerCore;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

/**
 * Shared badge formatting used by both the tab list and nametag mixins.
 *
 * v1.21.11 layout: the user wants ONE badge to the LEFT of the player's name,
 * ONE badge to the RIGHT, and a small gamemode icon embedded in each badge.
 * The gamemode icons are rendered through a custom font ({@code tiertagger:icons})
 * which maps Private-Use-Area codepoints to the bundled mode PNGs.
 */
public final class BadgeRenderer {
    private BadgeRenderer() {}

    private static final Identifier ICON_FONT =
        Identifier.of(ModeGlyphs.FONT_NAMESPACE, ModeGlyphs.FONT_PATH);

    /**
     * Reflectively-resolved {@code Style.withFont} setter.
     *
     * In MC 1.21.0-1.21.4 the signature is {@code withFont(Identifier)}.
     * From MC 1.21.5+ Mojang wrapped the font in {@code StyleSpriteSource}
     * so the only public overload becomes {@code withFont(StyleSpriteSource)}.
     * We probe both at class-init and use whichever exists, with the wrapper
     * type and constructor also discovered reflectively. If neither path
     * resolves we fall back to plain text (no icon glyph rendered) instead of
     * crashing the client.
     */
    private static final java.lang.reflect.Method WITH_FONT_IDENTIFIER;
    private static final java.lang.reflect.Method WITH_FONT_WRAPPED;
    private static final Object WRAPPED_FONT_INSTANCE;

    static {
        java.lang.reflect.Method idM = null;
        java.lang.reflect.Method wrM = null;
        Object wrInstance = null;
        try {
            idM = Style.class.getMethod("withFont", Identifier.class);
        } catch (Throwable ignored) {}
        if (idM == null) {
            // 1.21.5+ — find the only public withFont(...) and build its argument reflectively.
            for (java.lang.reflect.Method m : Style.class.getMethods()) {
                if (!"withFont".equals(m.getName()) || m.getParameterCount() != 1) continue;
                Class<?> argType = m.getParameterTypes()[0];
                if (argType == Identifier.class) continue;
                try {
                    // Most snapshots ship a record-style constructor that takes the Identifier directly.
                    java.lang.reflect.Constructor<?> ctor = argType.getConstructor(Identifier.class);
                    wrInstance = ctor.newInstance(ICON_FONT);
                    wrM = m;
                    break;
                } catch (Throwable ignored) {
                    // Try a static factory like StyleSpriteSource.of(Identifier).
                    try {
                        java.lang.reflect.Method factory = argType.getMethod("of", Identifier.class);
                        wrInstance = factory.invoke(null, ICON_FONT);
                        wrM = m;
                        break;
                    } catch (Throwable ignored2) {}
                }
            }
        }
        WITH_FONT_IDENTIFIER = idM;
        WITH_FONT_WRAPPED = wrM;
        WRAPPED_FONT_INSTANCE = wrInstance;
        if (WITH_FONT_IDENTIFIER == null && WITH_FONT_WRAPPED == null) {
            TierTaggerCore.LOGGER.warn(
                "[TierTagger] Could not resolve Style.withFont — gamemode icon glyphs disabled");
        }
    }

    private static Style applyIconFont(Style base) {
        try {
            if (WITH_FONT_IDENTIFIER != null) {
                return (Style) WITH_FONT_IDENTIFIER.invoke(base, ICON_FONT);
            }
            if (WITH_FONT_WRAPPED != null && WRAPPED_FONT_INSTANCE != null) {
                return (Style) WITH_FONT_WRAPPED.invoke(base, WRAPPED_FONT_INSTANCE);
            }
        } catch (Throwable ignored) {}
        return base;
    }

    /** Build a single inline glyph styled with the icon font, or empty if missing/disabled. */
    private static MutableText glyph(String mode) {
        if (mode == null) return Text.empty();
        TierConfig cfg = TierTaggerCore.config();
        if (cfg != null && cfg.disableIcons) return Text.empty();
        if (cfg != null && !cfg.showModeIcon) return Text.empty();
        String g = ModeGlyphs.glyphFor(mode);
        if (g == null || g.isEmpty()) return Text.empty();
        try {
            return Text.literal(g).setStyle(applyIconFont(Style.EMPTY));
        } catch (Throwable t) {
            return Text.empty();
        }
    }

    /**
     * Builds a single badge of the form:  [icon] [TIER]  with the optional
     * service short label ("MCT") prefixed (left badge) or suffixed (right badge).
     *
     * @param svc                  service the tier came from (for the short label / colour)
     * @param tier                 tier text such as "HT3"
     * @param mode                 winning gamemode id (e.g. "vanilla") for the icon
     * @param serviceLabelLeading  true → put the service short label BEFORE the badge
     */
    public static MutableText formatBadge(TierService svc, String tier, String mode,
                                          boolean serviceLabelLeading) {
        String label = TierFormat.label(tier);
        Formatting colour = TierFormat.colored()
            ? Formatting.byCode(TierTaggerCore.colourCodeFor(tier))
            : Formatting.GRAY;
        if (colour == null) colour = Formatting.GRAY;

        MutableText icon = glyph(mode);
        boolean hasIcon = icon != Text.empty()
            && icon.getString() != null && !icon.getString().isEmpty();

        MutableText core;
        if (TierFormat.useBrackets()) {
            core = Text.literal("[").formatted(Formatting.GRAY);
            if (hasIcon) core.append(icon).append(Text.literal(" ").formatted(Formatting.GRAY));
            core.append(Text.literal(label).formatted(colour, Formatting.BOLD));
            core.append(Text.literal("]").formatted(Formatting.GRAY));
        } else {
            core = Text.literal("");
            if (hasIcon) core.append(icon).append(Text.literal(" "));
            core.append(Text.literal(label).formatted(colour, Formatting.BOLD));
        }

        if (!TierFormat.showServiceLabel()) return core;

        // CRITICAL: must mask with 0xFFFFFF — Style.withColor(int) in MC 1.21.5+
        // throws IllegalArgumentException for any value outside 0..0xFFFFFF.
        MutableText svcLabel = Text.literal(svc.shortLabel).withColor(svc.accentArgb & 0xFFFFFF);
        return serviceLabelLeading
            ? svcLabel.append(Text.literal(" ")).append(core)
            : core.append(Text.literal(" ")).append(svcLabel);
    }

    // Backwards-compatible no-icon overload used by older callers.
    public static MutableText formatBadge(TierService svc, String tier, boolean serviceLabelLeading) {
        return formatBadge(svc, tier, null, serviceLabelLeading);
    }

    /**
     * Tab-list LEFT prefix — emits "<badge> " or {@code null} if no badge to show.
     * The mixin prepends this to the player's display name so the left badge
     * actually appears LEFT of the name (which used to be the bug — both
     * badges were appended to the right).
     */
    public static MutableText buildTabPrefix(TierConfig cfg, PlayerData data) {
        TierService leftSvc = cfg.leftServiceEnum();
        java.util.function.Predicate<String> tabFilter = cfg::isTabModeEnabled;
        TierTaggerCore.TierPick pick = TierTaggerCore.pickForService(data, leftSvc, tabFilter);
        if (pick == null || pick.tier == null || pick.tier.isBlank()) return null;
        return formatBadge(leftSvc, pick.tier, pick.mode, true).append(Text.literal(" "));
    }

    /**
     * Tab-list RIGHT suffix — emits " <badge>" or {@code null} if no badge to show.
     */
    public static MutableText buildTabSuffix(TierConfig cfg, PlayerData data) {
        if (!cfg.rightBadgeEnabled) return null;
        TierService rightSvc = cfg.rightServiceEnum();
        java.util.function.Predicate<String> tabFilter = cfg::isTabModeEnabled;
        TierTaggerCore.TierPick pick = TierTaggerCore.pickForService(data, rightSvc, tabFilter);
        if (pick == null || pick.tier == null || pick.tier.isBlank()) return null;
        return Text.literal(" ").append(formatBadge(rightSvc, pick.tier, pick.mode, false));
    }

    /**
     * Wraps the original nametag with [LEFT] orig [RIGHT]. Returns {@code null}
     * if neither badge has data so the caller can keep the original label.
     */
    public static MutableText wrapNametag(TierConfig cfg, PlayerData data, Text original) {
        TierService leftSvc  = cfg.leftServiceEnum();
        TierService rightSvc = cfg.rightServiceEnum();
        java.util.function.Predicate<String> nametagFilter = cfg::isNametagModeEnabled;
        TierTaggerCore.TierPick leftPick  = TierTaggerCore.pickForService(data, leftSvc, nametagFilter);
        TierTaggerCore.TierPick rightPick = cfg.rightBadgeEnabled
            ? TierTaggerCore.pickForService(data, rightSvc, nametagFilter) : null;
        boolean hasLeft  = leftPick  != null && leftPick.tier  != null && !leftPick.tier.isBlank();
        boolean hasRight = rightPick != null && rightPick.tier != null && !rightPick.tier.isBlank();
        if (!hasLeft && !hasRight) return null;

        MutableText out = Text.empty();
        if (hasLeft) {
            out.append(formatBadge(leftSvc, leftPick.tier, leftPick.mode, true))
               .append(Text.literal(" "));
        }
        out.append(original == null ? Text.empty() : original.copy());
        if (hasRight) {
            out.append(Text.literal(" "))
               .append(formatBadge(rightSvc, rightPick.tier, rightPick.mode, false));
        }
        return out;
    }
}

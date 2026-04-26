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
    /** Optional-of-Identifier variant added in 1.21.6 snapshots. */
    private static final java.lang.reflect.Method WITH_FONT_OPTIONAL;

    static {
        java.lang.reflect.Method idM = null;
        java.lang.reflect.Method wrM = null;
        java.lang.reflect.Method optM = null;
        Object wrInstance = null;

        // ── PRIMARY (1.21.11): direct compile-time reference to
        // new net.minecraft.text.StyleSpriteSource.Font(Identifier).
        // Loom remaps this at build time to the correct intermediary
        // (class_11719$class_11721.<init>(Lamo;)V) so it works on production
        // jars. Wrapped in try/catch so older snapshots that don't have
        // StyleSpriteSource fall through to the reflection paths below.
        try {
            wrInstance = ModernFontSourceHolder.SOURCE;
            wrM = Style.class.getMethod("withFont", ModernFontSourceHolder.SOURCE_TYPE);
        } catch (Throwable ignored) {}

        // ── FALLBACKS (older 1.21.x, kept for cross-version source compat) ──

        // 1) The simple public (Identifier) overload — present on 1.21.0–1.21.4.
        if (wrM == null) {
            try {
                idM = Style.class.getMethod("withFont", Identifier.class);
            } catch (Throwable ignored) {}
            if (idM == null) {
                try {
                    java.lang.reflect.Method m = Style.class.getDeclaredMethod("withFont", Identifier.class);
                    m.setAccessible(true);
                    idM = m;
                } catch (Throwable ignored) {}
            }
        }

        // 2) Optional<Identifier> overload — appeared briefly in 1.21.6 snapshots.
        if (wrM == null && idM == null) {
            try {
                optM = Style.class.getMethod("withFont", java.util.Optional.class);
            } catch (Throwable ignored) {}
        }

        // 3) Generic single-arg public withFont(<wrapper>) reflective probe
        //    that walks every nested class of the parameter type looking for
        //    a (Identifier) constructor or factory. Catches StyleSpriteSource$Font
        //    on snapshots where the holder above failed to load (e.g. in older
        //    versions where the inner class name differs).
        if (wrM == null && idM == null && optM == null) {
            for (java.lang.reflect.Method m : Style.class.getMethods()) {
                if (!"withFont".equals(m.getName()) || m.getParameterCount() != 1) continue;
                Class<?> argType = m.getParameterTypes()[0];
                if (argType == Identifier.class || argType == java.util.Optional.class) continue;
                Object inst = tryBuildFontWrapper(argType);
                if (inst != null) {
                    wrInstance = inst;
                    wrM = m;
                    break;
                }
            }
        }

        WITH_FONT_IDENTIFIER = idM;
        WITH_FONT_WRAPPED = wrM;
        WRAPPED_FONT_INSTANCE = wrInstance;
        WITH_FONT_OPTIONAL = optM;
        if (WITH_FONT_IDENTIFIER == null && WITH_FONT_WRAPPED == null && WITH_FONT_OPTIONAL == null) {
            TierTaggerCore.LOGGER.warn(
                "[TierTagger] Could not resolve Style.withFont — gamemode icon glyphs disabled");
        } else {
            TierTaggerCore.LOGGER.info(
                "[TierTagger] icon font binding: identifier={} wrapped={} optional={} wrapperType={}",
                WITH_FONT_IDENTIFIER != null,
                WITH_FONT_WRAPPED    != null,
                WITH_FONT_OPTIONAL   != null,
                WRAPPED_FONT_INSTANCE == null ? "null" : WRAPPED_FONT_INSTANCE.getClass().getName());
        }
    }

    /**
     * Walks the parameter type itself plus every declared/nested class looking
     * for a constructor or static factory that takes a single {@link Identifier}
     * and produces an instance assignable to {@code targetType}. Used as the
     * last-ditch fallback for the {@code withFont(<wrapper>)} overload when the
     * compile-time holder fails to load (e.g. on older 1.21.x).
     */
    private static Object tryBuildFontWrapper(Class<?> targetType) {
        java.util.List<Class<?>> candidates = new java.util.ArrayList<>();
        candidates.add(targetType);
        try {
            for (Class<?> nested : targetType.getDeclaredClasses()) {
                if (targetType.isAssignableFrom(nested)) candidates.add(nested);
            }
            for (Class<?> nested : targetType.getClasses()) {
                if (targetType.isAssignableFrom(nested) && !candidates.contains(nested)) candidates.add(nested);
            }
        } catch (Throwable ignored) {}
        for (Class<?> c : candidates) {
            try {
                java.lang.reflect.Constructor<?> ctor = c.getConstructor(Identifier.class);
                return ctor.newInstance(ICON_FONT);
            } catch (Throwable ignored) {}
            try {
                java.lang.reflect.Constructor<?> ctor = c.getDeclaredConstructor(Identifier.class);
                ctor.setAccessible(true);
                return ctor.newInstance(ICON_FONT);
            } catch (Throwable ignored) {}
            for (String fname : new String[] { "of", "create", "from", "font" }) {
                try {
                    java.lang.reflect.Method f = c.getMethod(fname, Identifier.class);
                    Object v = f.invoke(null, ICON_FONT);
                    if (v != null && targetType.isInstance(v)) return v;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /**
     * Isolated holder that resolves {@code StyleSpriteSource.Font(Identifier)}
     * via a compile-time reference, which Loom remaps to the correct
     * intermediary (class_11719$class_11721) at build time. This is the path
     * that finally makes gamemode icons render on 1.21.11 — the previous
     * reflection-only paths failed because StyleSpriteSource is an interface
     * (no constructor) and has no static factory taking Identifier, so every
     * probe returned null and applyIconFont silently no-op'd.
     *
     * <p>If the class doesn't exist on this MC version, accessing
     * {@link #SOURCE} throws {@link NoClassDefFoundError} which the caller
     * catches and falls back to the reflection probe.
     */
    private static final class ModernFontSourceHolder {
        static final net.minecraft.text.StyleSpriteSource SOURCE =
                new net.minecraft.text.StyleSpriteSource.Font(ICON_FONT);
        static final Class<?> SOURCE_TYPE = net.minecraft.text.StyleSpriteSource.class;
    }

    /**
     * Direct compile-time call to {@code Style.withFont(StyleSpriteSource)}.
     * Loom remaps both the method name and the {@code StyleSpriteSource}
     * parameter type to their intermediary names at build time, so the method
     * is found correctly in production — unlike the previous
     * {@code Style.class.getMethod("withFont", ...)} approach which always
     * returned {@code null} at runtime because method names are intermediary.
     *
     * Throws {@link NoClassDefFoundError} / {@link NoSuchMethodError} on MC
     * versions that don't have this overload; callers catch {@link Throwable}.
     */
    private static final class FontApplyHolder {
        static Style apply(Style base) {
            return base.withFont(ModernFontSourceHolder.SOURCE);
        }
    }

    private static Style applyIconFont(Style base) {
        // PRIMARY: direct compile-time call (1.21.5+). Loom remaps
        // Style.withFont and StyleSpriteSource to intermediary at build time.
        // The previous reflection paths used string name "withFont" which
        // doesn't exist in production (only intermediary names do).
        try {
            return FontApplyHolder.apply(base);
        } catch (Throwable ignored) {}

        // FALLBACK: reflection-based (development / older MC versions).
        try {
            if (WITH_FONT_WRAPPED != null && WRAPPED_FONT_INSTANCE != null) {
                return (Style) WITH_FONT_WRAPPED.invoke(base, WRAPPED_FONT_INSTANCE);
            }
            if (WITH_FONT_IDENTIFIER != null) {
                return (Style) WITH_FONT_IDENTIFIER.invoke(base, ICON_FONT);
            }
            if (WITH_FONT_OPTIONAL != null) {
                return (Style) WITH_FONT_OPTIONAL.invoke(base, java.util.Optional.of(ICON_FONT));
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
            // Force pure-white tint so the bitmap-font glyphs render at full
            // brightness in the tab list and above nametags. Without an
            // explicit colour the glyph inherits the surrounding chat style
            // (often Formatting.GRAY for tab names), which multiplies into
            // the icon and makes it look washed-out / too dark — exactly the
            // bug the user reported.
            Style s = applyIconFont(Style.EMPTY).withColor(0xFFFFFF);
            return Text.literal(g).setStyle(s);
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

        // Tier colour for the badge text. Previously we routed through
        // Formatting.byCode(colourCodeFor(tier)) which always snaps to one
        // of the 16 fixed vanilla colours (e.g. §6 gold = #FFAA00) — so the
        // user's exact Discord palette (HT1 #F1C40F, HT2 #A4B2C7, etc.) was
        // never actually shown on nametag badges or in the player tab list,
        // even after argbFor() was updated. We now style the label with the
        // FULL RGB value from argbFor() via Style.withColor(int) so name-tag
        // and tab-list badges match the in-GUI palette pixel-for-pixel.
        // CRITICAL: Style.withColor(int) in MC 1.21.5+ rejects any value
        // outside 0..0xFFFFFF, so the alpha byte must be masked off.
        boolean coloured = TierFormat.colored();
        int tierRgb = coloured
            ? (TierTaggerCore.argbFor(tier) & 0xFFFFFF)
            : 0xAAAAAA;
        Style tierStyle = Style.EMPTY.withColor(tierRgb).withBold(true);

        MutableText icon = glyph(mode);
        boolean hasIcon = icon != Text.empty()
            && icon.getString() != null && !icon.getString().isEmpty();

        MutableText core;
        if (TierFormat.useBrackets()) {
            core = Text.literal("[").formatted(Formatting.GRAY);
            if (hasIcon) core.append(icon).append(Text.literal(" ").formatted(Formatting.GRAY));
            core.append(Text.literal(label).setStyle(tierStyle));
            core.append(Text.literal("]").formatted(Formatting.GRAY));
        } else {
            core = Text.literal("");
            if (hasIcon) core.append(icon).append(Text.literal(" "));
            core.append(Text.literal(label).setStyle(tierStyle));
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
        TierTaggerCore.TierPick pick =
            TierTaggerCore.pickForService(data, leftSvc, tabFilter, cfg.leftMode);
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
        TierTaggerCore.TierPick pick =
            TierTaggerCore.pickForService(data, rightSvc, tabFilter, cfg.rightMode);
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
        TierTaggerCore.TierPick leftPick  =
            TierTaggerCore.pickForService(data, leftSvc, nametagFilter, cfg.leftMode);
        TierTaggerCore.TierPick rightPick = cfg.rightBadgeEnabled
            ? TierTaggerCore.pickForService(data, rightSvc, nametagFilter, cfg.rightMode) : null;
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

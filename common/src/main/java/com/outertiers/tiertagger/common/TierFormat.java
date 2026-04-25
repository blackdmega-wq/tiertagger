package com.outertiers.tiertagger.common;

/** Helpers for rendering badges according to the user's badge-format preference. */
public final class TierFormat {
    private TierFormat() {}

    /**
     * Returns the inner display text for a tier, e.g. "HT3".
     * Always preserves the full "HT" / "LT" prefix so the player tab list
     * never collapses to ambiguous "H3" / "L3" labels. Retired tiers come
     * in as "RHT3" / "RLT2" from {@link Ranking#label()} and are passed
     * through unchanged.
     */
    public static String label(String tier) {
        if (tier == null) return "";
        return tier.toUpperCase();
    }

    /** Whether to wrap the inner text in []. */
    public static boolean useBrackets() {
        if (TierTaggerCore.config() == null) return true;
        String f = TierTaggerCore.config().badgeFormat;
        return f == null || "bracket".equalsIgnoreCase(f) || "short".equalsIgnoreCase(f);
    }

    /** Whether badges should be colourised. */
    public static boolean colored() {
        return TierTaggerCore.config() == null || TierTaggerCore.config().coloredBadges;
    }

    /** Whether the per-service short label (MCT / OT / PVP / ST) should be prepended. */
    public static boolean showServiceLabel() {
        return TierTaggerCore.config() == null || TierTaggerCore.config().showServiceIcon;
    }
}

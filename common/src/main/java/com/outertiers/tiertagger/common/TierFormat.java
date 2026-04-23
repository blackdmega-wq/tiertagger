package com.outertiers.tiertagger.common;

/** Helpers for rendering badges according to the user's badge-format preference. */
public final class TierFormat {
    private TierFormat() {}

    /** Returns the inner display text for a tier, e.g. "HT3" or "H3". */
    public static String label(String tier) {
        if (tier == null) return "";
        String t = tier.toUpperCase();
        if (TierTaggerCore.config() == null) return t;
        switch (TierTaggerCore.config().badgeFormat == null ? "bracket" : TierTaggerCore.config().badgeFormat) {
            case "short":
                if (t.startsWith("HT")) return "H" + t.substring(2);
                if (t.startsWith("LT")) return "L" + t.substring(2);
                return t;
            default:
                return t;
        }
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
}

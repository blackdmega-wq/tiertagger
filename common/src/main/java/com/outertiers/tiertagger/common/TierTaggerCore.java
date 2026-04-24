package com.outertiers.tiertagger.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared platform-agnostic constants and helpers. */
public final class TierTaggerCore {
    public static final String MOD_ID  = "tiertagger";
    public static final String MOD_NAME = "TierTagger";
    public static final Logger LOGGER  = LoggerFactory.getLogger(MOD_NAME);

    private static TierConfig CONFIG;
    private static TierCache  CACHE;

    private TierTaggerCore() {}

    public static synchronized void init() {
        if (CONFIG != null) return;
        CONFIG = TierConfig.load();
        CACHE  = new TierCache(CONFIG);
        LOGGER.info("[TierTagger] core initialised — gamemode: {}, api: {}", CONFIG.gamemode, CONFIG.apiBase);
    }

    public static TierConfig config() { return CONFIG; }
    public static TierCache  cache()  { return CACHE; }

    /**
     * Picks the tier to display for an entry, applying:
     *   1) the "show peak tier" override, if enabled and available
     *   2) the configured gamemode
     *   3) fall-through to the player's highest tier across modes (when enabled)
     * Returns null when the player has no rated tiers at all.
     */
    public static String chooseTier(TierCache.Entry e) {
        if (e == null || e.missing) return null;
        TierConfig c = CONFIG;
        if (c == null) return null;

        if (c.showPeak && e.peakTier != null && !e.peakTier.isBlank() && !"-".equals(e.peakTier)) {
            return e.peakTier.toUpperCase();
        }

        String mode = c.gamemode == null ? "overall" : c.gamemode.toLowerCase();
        if ("overall".equals(mode)) {
            return pickHighest(e);
        }

        String t = e.tiers == null ? null : e.tiers.get(mode);
        if (t != null && !t.isBlank() && !"-".equals(t)) return t.toUpperCase();

        // Unranked in this mode -> fall-through to the highest tier we know about.
        if (c.fallthroughToHighest) {
            return pickHighest(e);
        }
        return null;
    }

    /** Returns highest tier from the entry's per-mode map (HT1 > LT1 > HT2 > ...). */
    public static String pickHighest(TierCache.Entry e) {
        if (e == null || e.tiers == null || e.tiers.isEmpty()) return null;
        String best = null;
        int bestScore = -1;
        for (String t : e.tiers.values()) {
            int s = score(t);
            if (s > bestScore) { bestScore = s; best = t; }
        }
        return best == null ? null : best.toUpperCase();
    }

    public static int score(String tier) {
        if (tier == null) return -1;
        switch (tier.toUpperCase()) {
            case "HT1": return 100; case "LT1": return 90;
            case "HT2": return 80;  case "LT2": return 70;
            case "HT3": return 60;  case "LT3": return 50;
            case "HT4": return 40;  case "LT4": return 30;
            case "HT5": return 20;  case "LT5": return 10;
            default: return 0;
        }
    }

    /** Returns ANSI / chat colour code (§ + char) for tier. */
    public static char colourCodeFor(String tier) {
        if (tier == null) return '7';
        String t = tier.toUpperCase();
        if (t.endsWith("1")) return 'd'; // light purple
        if (t.endsWith("2")) return 'c'; // red
        if (t.endsWith("3")) return '6'; // gold
        if (t.endsWith("4")) return 'e'; // yellow
        if (t.endsWith("5")) return 'a'; // green
        return '7';
    }

    /** Returns an ARGB int suitable for GUI fills (alpha 0xFF), matching colourCodeFor(). */
    public static int argbFor(String tier) {
        char c = colourCodeFor(tier);
        switch (c) {
            case 'd': return 0xFFFF55FF;
            case 'c': return 0xFFFF5555;
            case '6': return 0xFFFFAA00;
            case 'e': return 0xFFFFFF55;
            case 'a': return 0xFF55FF55;
            default:  return 0xFFAAAAAA;
        }
    }
}

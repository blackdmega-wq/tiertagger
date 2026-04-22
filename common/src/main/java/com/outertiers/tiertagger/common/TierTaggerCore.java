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

    /** Returns highest tier from the entry's per-mode map (HT1 > LT1 > HT2 > ...). */
    public static String pickHighest(TierCache.Entry e) {
        if (e == null || e.tiers.isEmpty()) return null;
        String best = null;
        int bestScore = -1;
        for (String t : e.tiers.values()) {
            int s = score(t);
            if (s > bestScore) { bestScore = s; best = t; }
        }
        return best;
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
}

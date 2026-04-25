package com.outertiers.tiertagger.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared platform-agnostic constants and helpers. */
public final class TierTaggerCore {
    public static final String MOD_ID  = "tiertagger";
    public static final String MOD_NAME = "TierTagger";
    public static final Logger LOGGER  = LoggerFactory.getLogger(MOD_NAME);
    public static final String MOD_VERSION = "1.21.11.2";

    private static TierConfig CONFIG;
    private static TierCache  CACHE;

    private TierTaggerCore() {}

    public static synchronized void init() {
        if (CONFIG != null) return;
        CONFIG = TierConfig.load();
        CACHE  = new TierCache(CONFIG);
        LOGGER.info("[TierTagger] core initialised — primary service: {}, mode: {}",
                CONFIG.primaryService, CONFIG.displayMode);
    }

    public static TierConfig config() { return CONFIG; }
    public static TierCache  cache()  { return CACHE; }

    // ============================================================================
    // Legacy single-tier helpers (used by the existing tab/nametag mixins until
    // they're migrated to the dual-badge API). These resolve against the user's
    // primary service.
    // ============================================================================

    public static String chooseTier(TierCache.Entry e) {
        if (e == null || e.missing) return null;
        TierConfig c = CONFIG;
        if (c == null) return null;

        if (c.showPeak && e.peakTier != null && !e.peakTier.isBlank() && !"-".equals(e.peakTier)) {
            return e.peakTier.toUpperCase();
        }

        String mode = c.displayMode == null ? "highest" : c.displayMode.toLowerCase();
        if ("highest".equals(mode) || "overall".equals(mode)) return pickHighest(e);

        String t = e.tiers == null ? null : e.tiers.get(mode);
        if (t != null && !t.isBlank() && !"-".equals(t)) return t.toUpperCase();

        if (c.fallthroughToHighest) return pickHighest(e);
        return null;
    }

    public static String pickHighest(TierCache.Entry e) {
        if (e == null || e.tiers == null || e.tiers.isEmpty()) return null;
        String best = null;
        int bestScore = -1;
        for (java.util.Map.Entry<String, String> me : e.tiers.entrySet()) {
            if (CONFIG != null && !CONFIG.isModeEnabled(me.getKey())) continue;
            int s = score(me.getValue());
            if (s > bestScore) { bestScore = s; best = me.getValue(); }
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

    // ============================================================================
    // Multi-service helpers — preferred for new code paths.
    // ============================================================================

    /** Returns the displayed tier label for one service ("HT3" / "LT4" / null). */
    public static String tierForService(PlayerData data, TierService service) {
        return tierForService(data, service, null);
    }

    /**
     * Lightweight value-class used by {@link #pickForService} so the badge
     * renderer can show the winning mode's icon next to the tier badge —
     * not just the tier text.
     */
    public static final class TierPick {
        public final String mode;   // e.g. "vanilla"
        public final String tier;   // e.g. "HT3"
        public TierPick(String mode, String tier) { this.mode = mode; this.tier = tier; }
    }

    /**
     * Same selection logic as {@link #tierForService(PlayerData, TierService,
     * java.util.function.Predicate)} but additionally returns the winning
     * mode so callers can render its icon. Returns {@code null} when no tier
     * is available.
     */
    public static TierPick pickForService(PlayerData data, TierService service,
                                          java.util.function.Predicate<String> modeFilter) {
        if (data == null || service == null || CONFIG == null) return null;
        ServiceData sd = data.get(service);
        if (sd == null || sd.missing) return null;

        String displayMode = CONFIG.displayMode == null ? "highest" : CONFIG.displayMode.toLowerCase();
        if ("highest".equals(displayMode) || "overall".equals(displayMode)) {
            String bestMode = null;
            Ranking best = null;
            for (java.util.Map.Entry<String, Ranking> me : sd.rankings.entrySet()) {
                String k = me.getKey();
                if (!CONFIG.isModeEnabled(k)) continue;
                if (modeFilter != null && !modeFilter.test(k)) continue;
                Ranking r = me.getValue();
                if (r == null || r.tierLevel <= 0) continue;
                if (best == null || r.score() > best.score()) { best = r; bestMode = k; }
            }
            return best == null ? null : new TierPick(bestMode, best.label());
        }
        if (modeFilter != null && !modeFilter.test(displayMode)) return null;
        Ranking r = sd.rankings.get(displayMode);
        if (r != null && r.tierLevel > 0) return new TierPick(displayMode, r.label());
        if (CONFIG.fallthroughToHighest) {
            String bestMode = null;
            Ranking best = null;
            for (java.util.Map.Entry<String, Ranking> me : sd.rankings.entrySet()) {
                String k = me.getKey();
                if (!CONFIG.isModeEnabled(k)) continue;
                if (modeFilter != null && !modeFilter.test(k)) continue;
                Ranking rr = me.getValue();
                if (rr == null || rr.tierLevel <= 0) continue;
                if (best == null || rr.score() > best.score()) { best = rr; bestMode = k; }
            }
            return best == null ? null : new TierPick(bestMode, best.label());
        }
        return null;
    }

    /**
     * Same as {@link #tierForService(PlayerData, TierService)} but additionally
     * filters which modes can win — used by the tab-list and nametag mixins so
     * the user can hide specific gamemodes per surface (e.g. show only Vanilla
     * in the tab list, only Crystal above nametags). When {@code modeFilter} is
     * {@code null} every globally-enabled mode is considered.
     */
    public static String tierForService(PlayerData data, TierService service,
                                        java.util.function.Predicate<String> modeFilter) {
        if (data == null || service == null || CONFIG == null) return null;
        ServiceData sd = data.get(service);
        if (sd == null || sd.missing) return null;

        String mode = CONFIG.displayMode == null ? "highest" : CONFIG.displayMode.toLowerCase();
        if ("highest".equals(mode) || "overall".equals(mode)) {
            Ranking best = null;
            for (java.util.Map.Entry<String, Ranking> me : sd.rankings.entrySet()) {
                String k = me.getKey();
                if (!CONFIG.isModeEnabled(k)) continue;
                if (modeFilter != null && !modeFilter.test(k)) continue;
                Ranking r = me.getValue();
                if (r == null || r.tierLevel <= 0) continue;
                if (best == null || r.score() > best.score()) best = r;
            }
            return best == null ? null : best.label();
        }
        // Specific-mode display: respect the filter so a hidden mode shows nothing.
        if (modeFilter != null && !modeFilter.test(mode)) return null;
        Ranking r = sd.rankings.get(mode);
        if (r != null && r.tierLevel > 0) return r.label();
        if (CONFIG.fallthroughToHighest) {
            // Apply the same filter when falling through to "highest".
            Ranking best = null;
            for (java.util.Map.Entry<String, Ranking> me : sd.rankings.entrySet()) {
                String k = me.getKey();
                if (!CONFIG.isModeEnabled(k)) continue;
                if (modeFilter != null && !modeFilter.test(k)) continue;
                Ranking rr = me.getValue();
                if (rr == null || rr.tierLevel <= 0) continue;
                if (best == null || rr.score() > best.score()) best = rr;
            }
            return best == null ? null : best.label();
        }
        return null;
    }

    public static char colourCodeFor(String tier) {
        if (tier == null) return '7';
        String t = tier.toUpperCase();
        if (t.endsWith("1")) return 'd';
        if (t.endsWith("2")) return 'c';
        if (t.endsWith("3")) return '6';
        if (t.endsWith("4")) return 'e';
        if (t.endsWith("5")) return 'a';
        return '7';
    }

    /**
     * OuterTiers-style colour palette. Distinguishes HT (vivid) from LT
     * (slightly desaturated) so HT3 doesn't look identical to LT3 at a glance.
     * Colours are tuned to match the tier badges shown on outertiers.com /
     * mctiers.com / pvptiers.com — the universal Minecraft tier-list scheme:
     * pink → red → orange → gold → green for tiers 1 → 5.
     */
    public static int argbFor(String tier) {
        if (tier == null) return 0xFFAAAAAA;
        String t = tier.toUpperCase();
        boolean high;
        char digit;
        if (t.startsWith("HT") && t.length() >= 3) { high = true;  digit = t.charAt(2); }
        else if (t.startsWith("LT") && t.length() >= 3) { high = false; digit = t.charAt(2); }
        else if (t.length() >= 1 && Character.isDigit(t.charAt(t.length() - 1))) {
            high  = true;
            digit = t.charAt(t.length() - 1);
        } else {
            return 0xFFAAAAAA;
        }
        switch (digit) {
            case '1': return high ? 0xFFED1AFF : 0xFFB048C2;   // T1: magenta / dusty pink
            case '2': return high ? 0xFFFF3F3F : 0xFFB13838;   // T2: vivid red / burgundy
            case '3': return high ? 0xFFFF9933 : 0xFFB97123;   // T3: orange / amber
            case '4': return high ? 0xFFFFCD33 : 0xFFB59425;   // T4: gold / mustard
            case '5': return high ? 0xFF58D352 : 0xFF3D8E3A;   // T5: lime / forest
            default:  return 0xFFAAAAAA;
        }
    }
}

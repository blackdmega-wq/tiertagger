package com.outertiers.tiertagger.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared platform-agnostic constants and helpers. */
public final class TierTaggerCore {
    public static final String MOD_ID  = "tiertagger";
    public static final String MOD_NAME = "TierTagger";
    public static final Logger LOGGER  = LoggerFactory.getLogger(MOD_NAME);
    public static final String MOD_VERSION = "1.21.11.51";

    private static TierConfig CONFIG;
    private static TierCache  CACHE;

    private TierTaggerCore() {}

    public static synchronized void init() {
        if (CONFIG != null) return;
        CONFIG = TierConfig.load();
        CACHE  = new TierCache(CONFIG);
        LOGGER.info("[TierTagger] core initialised — primary service: {}, mode: {}",
                CONFIG.primaryService, CONFIG.displayMode);
        // Kick off a background "is there a newer release on Modrinth?" check.
        // Result is cached in UpdateChecker; consumers call
        // UpdateChecker.isOutdated() / latestVersion() to read it. We never
        // block init on the network call.
        try { UpdateChecker.checkAsync(); } catch (Throwable t) {
            LOGGER.warn("[TierTagger] update check failed to start: {}", t.toString());
        }
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
        return pickForService(data, service, modeFilter, null);
    }

    /**
     * Same as {@link #pickForService(PlayerData, TierService, java.util.function.Predicate)}
     * but allows the caller to override which gamemode drives the badge for this
     * particular service. Used by the per-side ("Left Mode" / "Right Mode") config
     * options so a user can pin, e.g., the LEFT badge to MCTiers Vanilla while the
     * RIGHT badge follows MCTiers Sword. Pass {@code null} or {@code "highest"} to
     * fall back to the global {@link TierConfig#displayMode}.
     */
    public static TierPick pickForService(PlayerData data, TierService service,
                                          java.util.function.Predicate<String> modeFilter,
                                          String modeOverride) {
        if (data == null || service == null || CONFIG == null) return null;
        ServiceData sd = data.get(service);
        if (sd == null || sd.missing) return null;

        String displayMode;
        if (modeOverride != null && !modeOverride.isBlank()) {
            displayMode = modeOverride.toLowerCase();
        } else {
            displayMode = CONFIG.displayMode == null ? "highest" : CONFIG.displayMode.toLowerCase();
        }
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
        // Retired tiers (R prefix) are rendered white per the user palette.
        // Detect BEFORE stripping the prefix, otherwise "RHT2" would fall
        // through to the regular HT2 colour and the retired-marker would be
        // invisible in chat tags.
        if (t.startsWith("R") && t.length() > 1) {
            return 'f'; // §f = white (retired)
        }
        boolean high;
        char digit;
        if (t.startsWith("HT") && t.length() >= 3) { high = true;  digit = t.charAt(2); }
        else if (t.startsWith("LT") && t.length() >= 3) { high = false; digit = t.charAt(2); }
        else if (t.length() >= 1 && Character.isDigit(t.charAt(t.length() - 1))) {
            high  = true;
            digit = t.charAt(t.length() - 1);
        } else {
            return '7';
        }
        // Vanilla chat only has 16 fixed colour codes, so we pick the
        // closest legacy code to the rich RGB palette used by argbFor().
        switch (digit) {
            case '1': return '6';                  // T1 gold/dim-gold       → §6 gold
            case '2': return high ? '7' : '8';     // T2 light grey / grey   → §7 / §8
            case '3': return '6';                  // T3 orange/dark-orange  → §6 gold (closest)
            case '4': return high ? 'a' : '2';     // T4 green / dark green  → §a / §2
            case '5': return 'b';                  // T5 light blue          → §b aqua
            default:  return '7';
        }
    }

    /**
     * Tier badge colour palette. The current values come straight from the
     * user's Discord-supplied palette — the previous OuterTiers / MCTiers
     * pink→red→orange→gold→green scheme has been replaced wholesale.
     *
     * <pre>
     *   HT1 #F1C40F  LT1 #D4B354    (gold)
     *   HT2 #A4B2C7  LT2 #888D95    (silver / steel)
     *   HT3 #DF8746  LT3 #B36932    (bronze)
     *   HT4 #46DF5D  LT4 #319228    (green)
     *   HT5 #A4D5FF  LT5 #A4D5FF    (light blue, identical for HT/LT)
     *   Retired (R prefix)   #FFFFFF (white)
     * </pre>
     *
     * Retired-tier detection happens BEFORE the HT/LT split so labels like
     * "RHT3" / "RLT2" all render white instead of inheriting their base tier
     * colour — this is what the user asked for.
     */
    public static int argbFor(String tier) {
        if (tier == null) return 0xFFAAAAAA;
        String t = tier.toUpperCase();
        // Retired ("R" prefix) → look up "Retired" key (default white).
        if (t.startsWith("R") && t.length() > 1) {
            return readConfigColor("Retired", 0xFFFFFFFF);
        }
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
        // Built-in palette fallback when the user has NOT customised this tier.
        int builtin;
        switch (digit) {
            case '1': builtin = high ? 0xFFF1C40F : 0xFFD4B354; break;   // T1 gold
            case '2': builtin = high ? 0xFFA4B2C7 : 0xFF888D95; break;   // T2 silver / steel
            case '3': builtin = high ? 0xFFDF8746 : 0xFFB36932; break;   // T3 bronze
            case '4': builtin = high ? 0xFF46DF5D : 0xFF319228; break;   // T4 green
            case '5': builtin = 0xFFA4D5FF; break;                       // T5 light blue
            default:  return 0xFFAAAAAA;
        }
        String key = (high ? "HT" : "LT") + digit;
        return readConfigColor(key, builtin);
    }

    /**
     * Looks up a tier hex override in the live {@link TierConfig#tierColors}
     * map and returns its ARGB int, or the built-in palette colour when the
     * user has not customised that tier (or the saved hex is unparsable).
     */
    private static int readConfigColor(String key, int fallback) {
        try {
            if (CONFIG == null || CONFIG.tierColors == null) return fallback;
            String hex = CONFIG.tierColors.get(key);
            if (hex == null || hex.isBlank()) return fallback;
            return TierConfig.parseHexArgb(hex);
        } catch (Throwable t) {
            return fallback;
        }
    }
}

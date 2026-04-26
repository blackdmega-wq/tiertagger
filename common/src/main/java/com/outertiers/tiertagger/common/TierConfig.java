package com.outertiers.tiertagger.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Persisted user config. Backwards-compatible: old fields ({@code apiBase},
 * {@code gamemode}, {@code showInTab}, etc.) are still read & honoured so
 * users upgrading from 1.3.x don't lose their settings.
 *
 * Multi-service additions:
 *   - {@code services}        : per-service enabled flag
 *   - {@code primaryService}  : which service drives the legacy single-tier
 *                               badge in the tab list / nametag
 *   - {@code leftService}     : service used for the LEFT badge
 *   - {@code rightService}    : service used for the RIGHT badge
 *   - {@code rightBadgeEnabled} : show the right badge at all
 *   - {@code enabledModes}    : modes to include when computing "highest" tier
 *   - {@code displayMode}     : "highest" or specific mode name
 *   - {@code showServiceIcon} : prefix the badge with the service short label
 *   - {@code showModeIcon}    : show mode item icons in screens
 */
public class TierConfig {
    public static final String[] GAMEMODES = {
        "overall", "ogvanilla", "vanilla", "uhc", "pot",
        "nethop", "smp", "sword", "axe", "mace", "speed"
    };

    public static final String[] BADGE_FORMATS = { "bracket", "plain", "short" };

    // ------- legacy fields (kept for backward compat with old config files) -------
    public String  apiBase    = "https://outertiers-api.onrender.com";
    public String  gamemode   = "overall";
    public boolean showInTab  = true;
    public boolean showNametag = true;
    public boolean showPeak   = false;
    public boolean fallthroughToHighest = true;
    public boolean coloredBadges = true;
    public String  badgeFormat = "bracket";
    public int     cacheTtlSeconds = 300;

    // ------- new multi-service fields -------
    /** Per-service enabled state — default: every service on. */
    public Map<String, Boolean> services = defaultServices();
    /** Service used by the legacy single-tier helper. */
    public String  primaryService    = TierService.OUTERTIERS.id;
    public String  leftService       = TierService.OUTERTIERS.id;
    public String  rightService      = TierService.MCTIERS.id;
    public boolean rightBadgeEnabled = true;
    public boolean showServiceIcon   = true;
    public boolean showModeIcon      = true;
    /** Either "highest" or one of {@link #GAMEMODES} (excluding "overall" which means highest). */
    public String  displayMode       = "highest";
    /**
     * Per-side mode override for the LEFT badge. {@code "highest"} (default) means
     * "use whichever gamemode of {@link #leftService} the player ranks highest in".
     * Any other value (e.g. {@code "vanilla"}, {@code "sword"}) forces the badge
     * to read the tier from that specific gamemode of {@link #leftService}, with
     * {@link #fallthroughToHighest} controlling what happens when the player has
     * no rank in that mode.
     */
    public String  leftMode           = "highest";
    /** Per-side mode override for the RIGHT badge. See {@link #leftMode}. */
    public String  rightMode          = "highest";
    /**
     * Per-tier hex colour overrides shown on the "Tier Colors" tab in
     * {@code /tiertagger config}. Keys are tier labels ("HT1", "LT1", …,
     * "Retired"); values are RGB hex strings ("#F1C40F"). When {@code null}
     * or missing for a given tier, {@link TierTaggerCore#argbFor(String)}
     * falls back to its built-in palette.
     */
    public Map<String, String> tierColors = null;
    /** Modes considered when computing "highest" tier. Empty => use everything. */
    public List<String> enabledModes = new ArrayList<>();
    /** Modes shown in the player tab list. {@code null}/empty => follow {@link #enabledModes}. */
    public List<String> tabModes = null;
    /** Modes shown above player nametags. {@code null}/empty => follow {@link #enabledModes}. */
    public List<String> nametagModes = null;
    /** Cosmetic toggles surfaced in the config screen. */
    public boolean disableTiers      = false;
    public boolean disableIcons      = false;
    public boolean disableAnimations = false;

    // ------- new "Tiers config" UI fields (matches v1.21.11.31 redesign) -------

    /** When true, hide the tier badge in chat messages. */
    public boolean disableInChat = false;
    /**
     * When true, the separator between dual-badges and the player name is
     * coloured to match the dominant tier instead of using a plain grey "|".
     */
    public boolean dynamicSeparator = false;
    /**
     * Controls how the displayed tier is chosen when the player has tiers in
     * multiple gamemodes. One of:
     *   - "selected"         : only the user-selected gamemode tier is shown
     *   - "highest"          : only the player's highest tier is shown
     *   - "adaptive_highest" : prefer selected; fall back to highest if missing
     */
    public String displayedTiers = "adaptive_highest";
    /**
     * When true the mod will continuously inspect the local player's hotbar
     * to decide which gamemode to display, instead of cycling manually
     * with the "I" / "Z" hotkey.
     */
    public boolean autoKitDetect = false;
    /**
     * Where to place the "active" service badge relative to the player name.
     * One of "left", "center", "right". Default "right" matches the legacy
     * dual-badge layout (PvPTiers on the right of the nametag).
     */
    public String tierDisplayPosition = "right";

    public static final String[] DISPLAYED_TIER_MODES = {
        "selected", "highest", "adaptive_highest"
    };

    public static final String[] DISPLAY_POSITIONS = { "left", "center", "right" };

    /** Returns a human-readable label for the {@link #displayedTiers} value. */
    public static String displayedTiersLabel(String mode) {
        if (mode == null) return "Adaptive Highest";
        switch (mode.toLowerCase(Locale.ROOT)) {
            case "selected":         return "Selected";
            case "highest":          return "Highest";
            case "adaptive_highest":
            default:                 return "Adaptive Highest";
        }
    }

    private static Map<String, Boolean> defaultServices() {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (TierService s : TierService.values()) m.put(s.id, true);
        return m;
    }

    /** Tier label order used by the "Tier Colors" tab. */
    public static final String[] TIER_KEYS = {
        "HT1", "LT1", "HT2", "LT2", "HT3", "LT3",
        "HT4", "LT4", "HT5", "LT5", "Retired"
    };

    /**
     * Default tier hex colours — these are the user's existing palette baked
     * into {@link TierTaggerCore#argbFor(String)}. The "Tier Colors" tab seeds
     * its rows from this map and "Reset to Default" wipes overrides back to it.
     */
    public static Map<String, String> defaultTierColors() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("HT1", "#F1C40F");
        m.put("LT1", "#D4B354");
        m.put("HT2", "#A4B2C7");
        m.put("LT2", "#888D95");
        m.put("HT3", "#DF8746");
        m.put("LT3", "#B36932");
        m.put("HT4", "#46DF5D");
        m.put("LT4", "#319228");
        m.put("HT5", "#A4D5FF");
        m.put("LT5", "#A4D5FF");
        m.put("Retired", "#FFFFFF");
        return m;
    }

    /** Returns the saved hex string for a tier, or its default if not customised. */
    public String getTierColorHex(String tierKey) {
        if (tierKey == null) return "#FFFFFF";
        if (tierColors != null) {
            String v = tierColors.get(tierKey);
            if (v != null && !v.isBlank()) return v;
        }
        String def = defaultTierColors().get(tierKey);
        return def == null ? "#FFFFFF" : def;
    }

    /** Persists a tier colour override. Pass {@code null}/blank to clear. */
    public void setTierColorHex(String tierKey, String hex) {
        if (tierKey == null) return;
        if (tierColors == null) tierColors = new LinkedHashMap<>();
        if (hex == null || hex.isBlank()) tierColors.remove(tierKey);
        else tierColors.put(tierKey, normaliseHex(hex));
    }

    /** Forces {@code "#RRGGBB"} formatting (uppercase, 6 hex digits). */
    public static String normaliseHex(String raw) {
        if (raw == null) return "#FFFFFF";
        String s = raw.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() == 3) {
            char r = s.charAt(0), g = s.charAt(1), b = s.charAt(2);
            s = "" + r + r + g + g + b + b;
        }
        if (s.length() == 8) s = s.substring(2); // strip leading alpha if present
        if (s.length() != 6) return "#FFFFFF";
        StringBuilder sb = new StringBuilder("#");
        for (int i = 0; i < 6; i++) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f')) {
                sb.append(Character.toUpperCase(c));
            } else {
                return "#FFFFFF";
            }
        }
        return sb.toString();
    }

    /** Parses {@code "#RRGGBB"} into {@code 0xFFRRGGBB}; returns 0xFFFFFFFF on failure. */
    public static int parseHexArgb(String hex) {
        try {
            String s = normaliseHex(hex).substring(1);
            return 0xFF000000 | Integer.parseInt(s, 16);
        } catch (Throwable t) {
            return 0xFFFFFFFF;
        }
    }

    // ------- io -------
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path configDir = Path.of(".");

    public static void setConfigDir(Path dir) {
        if (dir != null) configDir = dir;
    }

    private static Path path() {
        return configDir.resolve("tiertagger.json");
    }

    public static TierConfig load() {
        Path p = path();
        if (Files.exists(p)) {
            try {
                String json = Files.readString(p);
                TierConfig cfg = GSON.fromJson(json, TierConfig.class);
                if (cfg != null) return cfg.normalise();
            } catch (Exception e) {
                TierTaggerCore.LOGGER.warn("[TierTagger] could not read config, using defaults: {}", e.getMessage());
            }
        }
        TierConfig cfg = new TierConfig();
        cfg.save();
        return cfg;
    }

    public TierConfig normalise() {
        if (gamemode == null) gamemode = "overall";
        if (apiBase  == null || apiBase.isBlank()) apiBase = "https://outertiers-api.onrender.com";
        if (cacheTtlSeconds <= 0) cacheTtlSeconds = 300;
        if (badgeFormat == null || !isValidBadgeFormat(badgeFormat)) badgeFormat = "bracket";

        // Multi-service field defaults (safe upgrade path)
        if (services == null || services.isEmpty()) services = defaultServices();
        else {
            // Ensure every known service has an entry (new services added in future updates).
            for (TierService s : TierService.values()) services.putIfAbsent(s.id, true);
        }
        if (primaryService == null || TierService.byId(primaryService) == null) primaryService = TierService.OUTERTIERS.id;
        if (leftService    == null || TierService.byId(leftService)    == null) leftService    = TierService.OUTERTIERS.id;
        if (rightService   == null || TierService.byId(rightService)   == null) rightService   = TierService.MCTIERS.id;
        if (displayMode    == null || displayMode.isBlank()) displayMode = "highest";
        if (leftMode       == null || leftMode.isBlank())    leftMode    = "highest";
        if (rightMode      == null || rightMode.isBlank())   rightMode   = "highest";
        if (enabledModes   == null) enabledModes = new ArrayList<>();
        if (displayedTiers == null || displayedTiers.isBlank()) displayedTiers = "adaptive_highest";
        else {
            String dt = displayedTiers.toLowerCase(Locale.ROOT);
            boolean ok = false;
            for (String v : DISPLAYED_TIER_MODES) if (v.equals(dt)) { ok = true; break; }
            displayedTiers = ok ? dt : "adaptive_highest";
        }
        if (tierDisplayPosition == null || tierDisplayPosition.isBlank()) tierDisplayPosition = "right";
        else {
            String tp = tierDisplayPosition.toLowerCase(Locale.ROOT);
            boolean ok = false;
            for (String v : DISPLAY_POSITIONS) if (v.equals(tp)) { ok = true; break; }
            tierDisplayPosition = ok ? tp : "right";
        }
        // tierColors: keep null when the user hasn't customised anything (saves
        // a few bytes in the json + signals "use defaults" to argbFor).
        if (tierColors != null) {
            // Re-normalise any user-supplied hex strings so weird input (e.g.
            // missing "#", alpha prefix, lowercase) is cleaned up on load.
            Map<String, String> cleaned = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : tierColors.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                cleaned.put(e.getKey(), normaliseHex(e.getValue()));
            }
            tierColors = cleaned;
        }

        return this;
    }

    public synchronized void save() {
        try {
            Files.writeString(path(), GSON.toJson(this));
        } catch (IOException e) {
            TierTaggerCore.LOGGER.warn("[TierTagger] could not save config: {}", e.getMessage());
        }
    }

    // ------- helpers -------

    public static boolean isValidGamemode(String g) {
        if (g == null) return false;
        for (String m : GAMEMODES) if (m.equalsIgnoreCase(g)) return true;
        return false;
    }

    public static boolean isValidBadgeFormat(String f) {
        if (f == null) return false;
        for (String x : BADGE_FORMATS) if (x.equalsIgnoreCase(f)) return true;
        return false;
    }

    public boolean isServiceEnabled(TierService s) {
        if (s == null || services == null) return false;
        Boolean b = services.get(s.id);
        return b == null ? true : b;
    }

    public void setServiceEnabled(TierService s, boolean enabled) {
        if (s == null) return;
        if (services == null) services = defaultServices();
        services.put(s.id, enabled);
    }

    public boolean isModeEnabled(String mode) {
        if (mode == null) return false;
        if (enabledModes == null || enabledModes.isEmpty()) return true; // default: all on
        for (String m : enabledModes) if (m.equalsIgnoreCase(mode)) return true;
        return false;
    }

    public void setModeEnabled(String mode, boolean enabled) {
        if (mode == null) return;
        if (enabledModes == null) enabledModes = new ArrayList<>();
        if (enabledModes.isEmpty()) {
            // Materialise the implicit "all on" set so we can flip individual entries off.
            for (String m : TierService.allKnownModes()) enabledModes.add(m.toLowerCase(Locale.ROOT));
        }
        String key = mode.toLowerCase(Locale.ROOT);
        enabledModes.removeIf(s -> s.equalsIgnoreCase(key));
        if (enabled) enabledModes.add(key);
    }

    /** Returns true if the given mode should be displayed in the tab list. */
    public boolean isTabModeEnabled(String mode) {
        if (mode == null) return false;
        if (tabModes == null || tabModes.isEmpty()) return isModeEnabled(mode);
        for (String m : tabModes) if (m.equalsIgnoreCase(mode)) return true;
        return false;
    }

    /** Returns true if the given mode should be displayed above nametags. */
    public boolean isNametagModeEnabled(String mode) {
        if (mode == null) return false;
        if (nametagModes == null || nametagModes.isEmpty()) return isModeEnabled(mode);
        for (String m : nametagModes) if (m.equalsIgnoreCase(mode)) return true;
        return false;
    }

    public void setTabModeEnabled(String mode, boolean enabled) {
        tabModes = setModeEnabledIn(tabModes, mode, enabled);
    }

    public void setNametagModeEnabled(String mode, boolean enabled) {
        nametagModes = setModeEnabledIn(nametagModes, mode, enabled);
    }

    private static List<String> setModeEnabledIn(List<String> list, String mode, boolean enabled) {
        if (mode == null) return list;
        if (list == null) list = new ArrayList<>();
        if (list.isEmpty()) {
            // Materialise the implicit "all on" set before flipping entries.
            for (String m : TierService.allKnownModes()) list.add(m.toLowerCase(Locale.ROOT));
        }
        String key = mode.toLowerCase(Locale.ROOT);
        list.removeIf(s -> s.equalsIgnoreCase(key));
        if (enabled) list.add(key);
        return list;
    }

    public TierService primaryServiceEnum()   { return TierService.byIdOr(primaryService, TierService.OUTERTIERS); }
    public TierService leftServiceEnum()      { return TierService.byIdOr(leftService,    TierService.OUTERTIERS); }
    public TierService rightServiceEnum()     { return TierService.byIdOr(rightService,   TierService.MCTIERS);    }

    public void resetToDefaults() {
        TierConfig def = new TierConfig();
        this.apiBase = def.apiBase;
        this.gamemode = def.gamemode;
        this.showInTab = def.showInTab;
        this.showNametag = def.showNametag;
        this.showPeak = def.showPeak;
        this.fallthroughToHighest = def.fallthroughToHighest;
        this.coloredBadges = def.coloredBadges;
        this.badgeFormat = def.badgeFormat;
        this.cacheTtlSeconds = def.cacheTtlSeconds;
        this.services = defaultServices();
        this.primaryService    = def.primaryService;
        this.leftService       = def.leftService;
        this.rightService      = def.rightService;
        this.rightBadgeEnabled = def.rightBadgeEnabled;
        this.showServiceIcon   = def.showServiceIcon;
        this.showModeIcon      = def.showModeIcon;
        this.displayMode       = def.displayMode;
        this.leftMode          = def.leftMode;
        this.rightMode         = def.rightMode;
        this.tierColors        = null; // null => use built-in palette
        this.enabledModes      = new ArrayList<>();
        this.tabModes          = null;
        this.nametagModes      = null;
        this.disableTiers      = def.disableTiers;
        this.disableIcons      = def.disableIcons;
        this.disableAnimations = def.disableAnimations;
        this.disableInChat     = def.disableInChat;
        this.dynamicSeparator  = def.dynamicSeparator;
        this.displayedTiers    = def.displayedTiers;
        this.autoKitDetect     = def.autoKitDetect;
        this.tierDisplayPosition = def.tierDisplayPosition;
    }
}

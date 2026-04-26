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

    private static Map<String, Boolean> defaultServices() {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (TierService s : TierService.values()) m.put(s.id, true);
        return m;
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
        this.enabledModes      = new ArrayList<>();
        this.tabModes          = null;
        this.nametagModes      = null;
        this.disableTiers      = def.disableTiers;
        this.disableIcons      = def.disableIcons;
        this.disableAnimations = def.disableAnimations;
    }
}

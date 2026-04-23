package com.outertiers.tiertagger.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class TierConfig {
    public static final String[] GAMEMODES = {
        "overall", "ogvanilla", "vanilla", "uhc", "pot",
        "nethop", "smp", "sword", "axe", "mace", "speed"
    };

    public static final String[] BADGE_FORMATS = { "bracket", "plain", "short" };

    public String  apiBase    = "https://outertiers-api.onrender.com";
    public String  gamemode   = "overall";
    public boolean showInTab  = true;
    public boolean showNametag = true;
    public boolean showPeak   = false;
    public boolean fallthroughToHighest = true;
    public boolean coloredBadges = true;
    public String  badgeFormat = "bracket";
    public int     cacheTtlSeconds = 300;

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
        return this;
    }

    public synchronized void save() {
        try {
            Files.writeString(path(), GSON.toJson(this));
        } catch (IOException e) {
            TierTaggerCore.LOGGER.warn("[TierTagger] could not save config: {}", e.getMessage());
        }
    }

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
    }
}

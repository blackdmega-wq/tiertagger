package com.outertiers.tiertagger.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class TierConfig {
    public static final String[] GAMEMODES = {
        "overall", "ogvanilla", "vanilla", "uhc", "pot",
        "nethop", "smp", "sword", "axe", "mace", "speed"
    };

    public String  apiBase    = "https://outertiers-api.onrender.com";
    public String  gamemode   = "overall";
    public boolean showInTab  = true;
    public boolean showPeak   = false;
    public int     cacheTtlSeconds = 300;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("tiertagger.json");
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
}

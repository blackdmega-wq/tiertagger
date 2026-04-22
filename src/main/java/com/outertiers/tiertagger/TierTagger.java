package com.outertiers.tiertagger;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TierTagger implements ClientModInitializer {
    public static final String MOD_ID = "tiertagger";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static TierConfig CONFIG;
    private static TierCache CACHE;

    public static TierConfig config() { return CONFIG; }
    public static TierCache  cache()  { return CACHE; }

    @Override
    public void onInitializeClient() {
        CONFIG = TierConfig.load();
        CACHE  = new TierCache(CONFIG);
        TierTaggerCommand.register();
        LOGGER.info("[TierTagger] initialised — gamemode: {}, api: {}", CONFIG.gamemode, CONFIG.apiBase);
    }
}

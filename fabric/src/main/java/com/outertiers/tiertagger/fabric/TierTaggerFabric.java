package com.outertiers.tiertagger.fabric;

import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierTaggerCore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public class TierTaggerFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        TierConfig.setConfigDir(FabricLoader.getInstance().getConfigDir());
        TierTaggerCore.init();
        PendingScreen.register();
        TierTaggerFabricCommand.register();
    }
}

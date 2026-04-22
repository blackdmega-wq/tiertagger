package com.outertiers.tiertagger.fabric;

import com.outertiers.tiertagger.common.TierTaggerCore;
import net.fabricmc.api.ClientModInitializer;

public class TierTaggerFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        TierTaggerCore.init();
        TierTaggerFabricCommand.register();
    }
}

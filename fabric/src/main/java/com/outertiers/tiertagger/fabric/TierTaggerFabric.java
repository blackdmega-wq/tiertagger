package com.outertiers.tiertagger.fabric;

import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierTaggerCore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Fabric client entrypoint. Each step is wrapped in its own try/catch so a
 * single failing optional subsystem (e.g. keybind registration on a stripped-
 * down Fabric API) never propagates out of {@code onInitializeClient} — that
 * would prevent the mod from loading and, on some launchers, manifests as a
 * black screen on world join. The user gets a clear WARN in latest.log
 * instead and the rest of the mod still works.
 */
public class TierTaggerFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        try {
            TierConfig.setConfigDir(FabricLoader.getInstance().getConfigDir());
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] could not set config dir: {}", t.toString());
        }
        try {
            TierTaggerCore.init();
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.error("[TierTagger] core init failed (mod will be inert)", t);
            return;
        }
        try {
            PendingScreen.register();
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] PendingScreen register failed: {}", t.toString());
        }
        try {
            TierKeybinds.register();
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] keybind register failed: {}", t.toString());
        }
        try {
            TierTaggerFabricCommand.register();
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] command register failed: {}", t.toString());
        }
        try {
            TierChatDecorator.register();
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] chat decorator register failed: {}", t.toString());
        }
    }
}

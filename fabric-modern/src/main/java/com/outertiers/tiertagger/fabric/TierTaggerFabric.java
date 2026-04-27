package com.outertiers.tiertagger.fabric;

import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.common.UpdateChecker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

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
            CycleKeyCapture.register();
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] cycle-key capture register failed: {}", t.toString());
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
        try {
            registerOutdatedVersionNotifier();
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] outdated-version notifier register failed: {}", t.toString());
        }
    }

    /**
     * Wires the {@link UpdateChecker} into the in-game chat: when the local
     * player joins a world / server, we wait ~3 seconds (so the background
     * Modrinth check has time to finish AND so we don't race with the
     * server's own welcome messages) and then deliver a one-shot system
     * message asking the user to update if they're on an outdated version.
     */
    private static void registerOutdatedVersionNotifier() {
        // Tick counter that arms when JOIN fires, fires the chat sink after
        // ~60 client ticks (≈3 seconds), then disarms until the next join.
        final int[] countdown = { -1 };
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            countdown[0] = 60;
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (countdown[0] < 0) return;
            countdown[0]--;
            if (countdown[0] != 0) return;
            try {
                Minecraft mc = client;
                if (mc == null || mc.player == null) return;
                UpdateChecker.notifyPlayerIfOutdated(line ->
                    mc.player.displayClientMessage(Component.literal(line), false));
            } catch (Throwable t) {
                TierTaggerCore.LOGGER.debug("[TierTagger] outdated-version chat send failed", t);
            }
        });
    }
}

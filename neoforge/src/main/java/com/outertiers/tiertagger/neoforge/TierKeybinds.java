package com.outertiers.tiertagger.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.neoforge.screen.TierConfigScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/**
 * NeoForge twin of the Fabric {@code TierKeybinds} helper. Registers a
 * client-side key binding under <em>Options &rarr; Controls &rarr; Key Binds
 * &rarr; TierTagger</em> that opens the config screen.
 *
 * <p>The binding is registered on the mod event bus via
 * {@link RegisterKeyMappingsEvent}; the press itself is polled on the game
 * bus via {@link ClientTickEvent.Post}.</p>
 */
public final class TierKeybinds {
    private static volatile boolean registered = false;
    private static final KeyMapping OPEN_CONFIG = new KeyMapping(
            "key.tiertagger.config",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "key.categories.tiertagger"
    );

    private TierKeybinds() {}

    public static synchronized void register(IEventBus modBus) {
        if (registered) return;
        registered = true;
        try {
            modBus.addListener((RegisterKeyMappingsEvent evt) -> evt.register(OPEN_CONFIG));
            NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post evt) -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc == null) return;
                while (OPEN_CONFIG.consumeClick()) {
                    if (mc.screen != null) continue;
                    PendingScreen.open(new TierConfigScreen(null));
                }
            });
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] could not register key bindings: {}", t.toString());
        }
    }
}

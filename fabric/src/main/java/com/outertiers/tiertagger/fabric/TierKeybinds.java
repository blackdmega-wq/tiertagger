package com.outertiers.tiertagger.fabric;

import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.fabric.screen.TierConfigScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * Registers TierTagger's client-side key bindings. The user can rebind any of
 * them under <em>Options &rarr; Controls &rarr; Key Binds &rarr; TierTagger</em>.
 *
 * <p>Currently only one binding is exposed:
 * <ul>
 *   <li>{@code key.tiertagger.config} &mdash; opens the TierTagger config
 *       screen, default key {@code K}. Set to {@code GLFW_KEY_UNKNOWN} (the
 *       in-game "Not bound" entry) to disable.</li>
 * </ul>
 */
public final class TierKeybinds {
    private static volatile boolean registered = false;
    private static KeyBinding openConfig;

    private TierKeybinds() {}

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        try {
            openConfig = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                    "key.tiertagger.config",
                    InputUtil.Type.KEYSYM,
                    GLFW.GLFW_KEY_K,
                    "key.categories.tiertagger"
            ));
            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                if (openConfig == null || client == null) return;
                // Drain wasPressed() so a held key opens the screen exactly
                // once per press.
                while (openConfig.wasPressed()) {
                    if (client.currentScreen != null) continue;
                    PendingScreen.open(new TierConfigScreen(null));
                }
            });
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] could not register key bindings: {}", t.toString());
        }
    }
}

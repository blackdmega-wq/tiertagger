package com.outertiers.tiertagger.fabric;

import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.fabric.screen.TierConfigScreen;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import org.lwjgl.glfw.GLFW;

/**
 * Click-to-bind capture for the "Cycle Mode Key" button on
 * {@link TierConfigScreen}.
 *
 * Implemented via Fabric's {@code ScreenKeyboardEvents.allowKeyPress} hook
 * — its callback signature {@code (Screen, int, int, int)} is stable across
 * every supported Minecraft version, so we don't have to override
 * {@code Screen.keyPressed} (whose own signature changed in MC 1.21.11) and
 * we don't have to mixin into the obfuscated {@code Keyboard.onKey} (whose
 * mapped name varies).
 *
 * Registered exactly once from {@code TierTaggerFabric#onInitializeClient}.
 */
public final class CycleKeyCapture {
    private CycleKeyCapture() {}

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof TierConfigScreen)) return;

            // Fires BEFORE the screen's own keyPressed dispatch. Returning
            // false here cancels propagation so ESC won't close the screen
            // and the captured key won't bleed into other widgets.
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, key, scancode, modifiers) -> {
                if (!TierConfigScreen.CAPTURING_CYCLE_KEY) return true;

                // ESC cancels capture, leaves the existing bind untouched.
                if (key == GLFW.GLFW_KEY_ESCAPE) {
                    TierConfigScreen.CAPTURING_CYCLE_KEY = false;
                    TierConfigScreen.refreshCycleKeyButtonLabel();
                    return false;
                }

                // Any other key becomes the new bind.
                try {
                    TierKeybinds.setCycleKeyCode(key);
                } catch (Throwable t) {
                    TierTaggerCore.LOGGER.warn("[TierTagger] failed to set cycle key bind: {}", t.toString());
                }
                TierConfigScreen.CAPTURING_CYCLE_KEY = false;
                TierConfigScreen.refreshCycleKeyButtonLabel();
                return false;
            });
        });
    }
}

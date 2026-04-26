package com.outertiers.tiertagger.fabric;

import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.fabric.screen.TierConfigScreen;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.input.KeyInput;
import org.lwjgl.glfw.GLFW;

/**
 * Click-to-bind capture for the "Cycle Mode Key" button on
 * {@link TierConfigScreen}.
 *
 * Implemented via Fabric's {@code ScreenKeyboardEvents.allowKeyPress} hook,
 * which in MC 1.21.11+ takes a {@link KeyInput} record exposing
 * {@code key()}, {@code scancode()} and {@code modifiers()}.
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
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, context) -> {
                if (!TierConfigScreen.CAPTURING_CYCLE_KEY) return true;

                int key = context.key();

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

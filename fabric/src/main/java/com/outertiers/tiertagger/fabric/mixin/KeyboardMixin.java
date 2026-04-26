package com.outertiers.tiertagger.fabric.mixin;

import com.outertiers.tiertagger.fabric.TierKeybinds;
import com.outertiers.tiertagger.fabric.screen.TierConfigScreen;
import net.minecraft.client.Keyboard;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks the GLFW key callback to implement the click-to-bind feature on the
 * TierTagger config screen WITHOUT having to override Screen.keyPressed —
 * which has changed signature between MC 1.21.5 and MC 1.21.6+ (KeyInput
 * vs the older int triple).
 *
 * The Keyboard.onKey(long, int, int, int, int) signature is the raw GLFW
 * callback shape and is stable across all supported MC versions.
 */
@Mixin(Keyboard.class)
public abstract class KeyboardMixin {
    @Inject(method = "onKey(JIIII)V", at = @At("HEAD"), cancellable = true)
    private void tiertagger$captureCycleKey(long window, int key, int scancode,
                                            int action, int modifiers,
                                            CallbackInfo ci) {
        if (!TierConfigScreen.CAPTURING_CYCLE_KEY) return;
        // We only act on PRESS — ignore RELEASE / REPEAT so the bind is
        // stable and we don't capture a release event.
        if (action != GLFW.GLFW_PRESS) return;

        // ESC cancels capture and leaves the existing bind untouched.
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            TierConfigScreen.CAPTURING_CYCLE_KEY = false;
            TierConfigScreen.refreshCycleKeyButtonLabel();
            ci.cancel();
            return;
        }

        // Any other key becomes the new bind.
        try { TierKeybinds.setCycleKeyCode(key); } catch (Throwable ignored) {}
        TierConfigScreen.CAPTURING_CYCLE_KEY = false;
        TierConfigScreen.refreshCycleKeyButtonLabel();
        ci.cancel();
    }
}

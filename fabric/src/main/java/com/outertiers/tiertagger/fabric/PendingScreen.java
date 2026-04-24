package com.outertiers.tiertagger.fabric;

import com.outertiers.tiertagger.common.TierTaggerCore;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Defers a {@code setScreen(...)} call until after the chat screen has finished
 * closing itself, so that opening a screen via a client command actually works.
 *
 * <p>Calling {@code mc.setScreen(new MyScreen())} directly inside a command
 * handler (or even via {@code mc.send(...)}) races against Minecraft's own
 * chat-close, which calls {@code setScreen(null)} after the command returns —
 * and that null-set wins, leaving the player back in the world with nothing
 * open. Queueing the open onto the next client tick avoids the race.</p>
 */
public final class PendingScreen {
    private static final AtomicReference<Screen> PENDING = new AtomicReference<>();
    private static volatile boolean registered = false;

    private PendingScreen() {}

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            Screen s = PENDING.getAndSet(null);
            if (s == null) return;
            try {
                client.setScreen(s);
            } catch (Throwable t) {
                TierTaggerCore.LOGGER.warn("[TierTagger] failed to open pending screen: {}", t.toString());
            }
        });
    }

    /** Schedules {@code screen} to be shown on the next client tick. */
    public static void open(Screen screen) {
        if (screen == null) return;
        PENDING.set(screen);
        // Nudge the client so the tick handler runs ASAP even if the chat
        // screen would otherwise hold rendering for a frame.
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null) mc.execute(() -> {});
    }
}

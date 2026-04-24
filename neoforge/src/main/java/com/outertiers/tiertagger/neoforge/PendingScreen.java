package com.outertiers.tiertagger.neoforge;

import com.outertiers.tiertagger.common.TierTaggerCore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.concurrent.atomic.AtomicReference;

/**
 * NeoForge twin of the Fabric {@code PendingScreen} helper. Defers a
 * {@code Minecraft#setScreen} call to the next client tick so that the
 * chat-screen close that follows a client command does not stomp on the
 * screen we just opened.
 */
public final class PendingScreen {
    private static final AtomicReference<Screen> PENDING = new AtomicReference<>();
    private static volatile boolean registered = false;

    private PendingScreen() {}

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post evt) -> {
            Screen s = PENDING.getAndSet(null);
            if (s == null) return;
            try {
                Minecraft.getInstance().setScreen(s);
            } catch (Throwable t) {
                TierTaggerCore.LOGGER.warn("[TierTagger] failed to open pending screen: {}", t.toString());
            }
        });
    }

    public static void open(Screen screen) {
        if (screen == null) return;
        PENDING.set(screen);
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.execute(() -> {});
    }
}

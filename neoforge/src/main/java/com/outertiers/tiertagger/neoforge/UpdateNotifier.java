package com.outertiers.tiertagger.neoforge;

import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.common.UpdateChecker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.ChatFormatting;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NeoForge counterpart to the Fabric {@code UpdateNotifier}: kicks off the
 * background GitHub release check and posts a one-shot chat notice to the
 * local player on world/server join when an outdated jar is detected.
 */
public final class UpdateNotifier {

    private static final AtomicBoolean NOTIFIED   = new AtomicBoolean(false);
    private static volatile boolean    REGISTERED = false;

    private UpdateNotifier() {}

    public static synchronized void register() {
        if (REGISTERED) return;
        REGISTERED = true;

        String currentVersion = readVersion("tiertagger");
        String mcVersion      = readVersion("minecraft");

        UpdateChecker.checkInBackground(currentVersion, mcVersion);

        NeoForge.EVENT_BUS.register(new UpdateNotifier());
    }

    @SubscribeEvent
    public void onJoin(ClientPlayerNetworkEvent.LoggingIn event) {
        try { sendNoticeIfNeeded(); }
        catch (Throwable t) {
            TierTaggerCore.LOGGER.debug("[TierTagger] update notice send failed: {}", t.toString());
        }
    }

    private static void sendNoticeIfNeeded() {
        TierConfig cfg = TierTaggerCore.config();
        if (cfg == null || !cfg.notifyOnUpdate) return;

        UpdateChecker.Result r = UpdateChecker.latest();
        if (r.status != UpdateChecker.Status.UPDATE_AVAILABLE) return;
        if (!NOTIFIED.compareAndSet(false, true)) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            LocalPlayer p = mc.player;
            if (p == null) return;
            p.displayClientMessage(buildMessage(r), false);
        });
    }

    private static MutableComponent buildMessage(UpdateChecker.Result r) {
        String url     = r.downloadUrl != null ? r.downloadUrl : UpdateChecker.DOWNLOAD_URL;
        String current = r.currentVersion != null ? r.currentVersion : "?";
        String latest  = r.latestVersion  != null ? r.latestVersion  : "?";

        MutableComponent prefix = Component.literal("[TierTagger] ")
            .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));
        MutableComponent body = Component.literal("A new version is available — ")
            .withStyle(s -> s.withColor(ChatFormatting.YELLOW));
        MutableComponent vers = Component.literal(latest)
            .withStyle(s -> s.withColor(ChatFormatting.WHITE).withBold(true));
        MutableComponent sep = Component.literal(" (you have " + current + "). ")
            .withStyle(s -> s.withColor(ChatFormatting.GRAY));

        Style linkStyle = Style.EMPTY
            .withColor(ChatFormatting.AQUA)
            .withUnderlined(true)
            .withClickEvent(openUrl(url))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(url)));
        MutableComponent link = Component.literal("[Download here]").withStyle(linkStyle);

        return prefix.append(body).append(vers).append(sep).append(link);
    }

    /**
     * Reflectively builds an OPEN_URL click-event so we stay compatible with
     * both the legacy enum-based ClickEvent and the new sealed-record subtypes.
     */
    @SuppressWarnings("unchecked")
    private static ClickEvent openUrl(String url) {
        try {
            try {
                Class<?> openUrl = Class.forName("net.minecraft.network.chat.ClickEvent$OpenUrl");
                return (ClickEvent) openUrl.getConstructor(URI.class).newInstance(URI.create(url));
            } catch (ClassNotFoundException ignored) { /* fall through */ }

            Class<?> action = Class.forName("net.minecraft.network.chat.ClickEvent$Action");
            Object   open   = action.getMethod("valueOf", String.class).invoke(null, "OPEN_URL");
            return (ClickEvent) ClickEvent.class.getConstructor(action, String.class)
                .newInstance(open, url);
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.debug("[TierTagger] could not build click-event: {}", t.toString());
            return null;
        }
    }

    private static String readVersion(String modId) {
        try {
            return ModList.get().getModContainerById(modId)
                .map(c -> c.getModInfo().getVersion().toString()).orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }
}

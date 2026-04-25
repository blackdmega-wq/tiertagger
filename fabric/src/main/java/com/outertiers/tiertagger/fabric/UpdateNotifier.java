package com.outertiers.tiertagger.fabric;

import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.common.UpdateChecker;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wires the platform-agnostic {@link UpdateChecker} into Fabric:
 *   1. kicks off the background HTTP check at launch, using the actual jar
 *      version reported by Fabric Loader (so the comparison stays accurate
 *      even if {@link TierTaggerCore#MOD_VERSION} drifts), and
 *   2. listens for {@link ClientPlayConnectionEvents#JOIN} so the player
 *      sees a single, dismissable chat notice the first time they connect
 *      to any world or server while running an outdated build.
 *
 * <p>The chat message includes a clickable link to the latest release
 * download page. The notice fires at most once per game session — relaunch
 * to see it again.</p>
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

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            try { sendNoticeIfNeeded(client); }
            catch (Throwable t) {
                TierTaggerCore.LOGGER.debug("[TierTagger] update notice send failed: {}", t.toString());
            }
        });
    }

    /**
     * Reads {@link UpdateChecker#latest()} and posts a chat message when an
     * update is available, the user opted in, and we haven't already notified
     * during this session.
     */
    private static void sendNoticeIfNeeded(MinecraftClient client) {
        TierConfig cfg = TierTaggerCore.config();
        if (cfg == null || !cfg.notifyOnUpdate) return;

        UpdateChecker.Result r = UpdateChecker.latest();
        if (r.status != UpdateChecker.Status.UPDATE_AVAILABLE) return;
        if (!NOTIFIED.compareAndSet(false, true)) return;

        // Defer slightly so the message arrives after the server's join chatter.
        client.execute(() -> {
            ClientPlayerEntity p = client.player;
            if (p == null) return;
            p.sendMessage(buildMessage(r), false);
        });
    }

    private static MutableText buildMessage(UpdateChecker.Result r) {
        String url     = r.downloadUrl != null ? r.downloadUrl : UpdateChecker.DOWNLOAD_URL;
        String current = r.currentVersion != null ? r.currentVersion : "?";
        String latest  = r.latestVersion  != null ? r.latestVersion  : "?";

        MutableText prefix = Text.literal("[TierTagger] ").setStyle(Style.EMPTY.withColor(Formatting.GOLD).withBold(true));
        MutableText body   = Text.literal("A new version is available — ").setStyle(Style.EMPTY.withColor(Formatting.YELLOW));
        MutableText vers   = Text.literal(latest).setStyle(Style.EMPTY.withColor(Formatting.WHITE).withBold(true));
        MutableText sep    = Text.literal(" (you have " + current + "). ").setStyle(Style.EMPTY.withColor(Formatting.GRAY));

        Style linkStyle = Style.EMPTY
            .withColor(Formatting.AQUA)
            .withUnderline(true)
            .withClickEvent(openUrl(url))
            .withHoverEvent(new HoverEvent.ShowText(Text.literal(url)));
        MutableText link = Text.literal("[Download here]").setStyle(linkStyle);

        return prefix.append(body).append(vers).append(sep).append(link);
    }

    /**
     * Builds a click-event that opens the given URL. The {@code ClickEvent}
     * API has shifted across recent MC versions (sealed-record subclasses
     * vs. enum constants vs. constructors); we resolve through reflection so
     * the same code path compiles and runs across the build matrix.
     */
    @SuppressWarnings("unchecked")
    private static ClickEvent openUrl(String url) {
        try {
            // 1.21.6+ — sealed-record subclass: ClickEvent.OpenUrl(URI)
            try {
                Class<?> openUrl = Class.forName("net.minecraft.text.ClickEvent$OpenUrl");
                return (ClickEvent) openUrl.getConstructor(URI.class).newInstance(URI.create(url));
            } catch (ClassNotFoundException ignored) { /* fall through */ }

            // <=1.21.5 — public ClickEvent(Action, String) where Action is an enum
            Class<?> action = Class.forName("net.minecraft.text.ClickEvent$Action");
            Object   open   = action.getMethod("valueOf", String.class).invoke(null, "OPEN_URL");
            return (ClickEvent) ClickEvent.class.getConstructor(action, String.class)
                .newInstance(open, url);
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.debug("[TierTagger] could not build click-event: {}", t.toString());
            return null;
        }
    }

    /** Reads the mod-container version for the given mod-id, or {@code null}. */
    private static String readVersion(String modId) {
        try {
            Optional<ModContainer> mc = FabricLoader.getInstance().getModContainer(modId);
            return mc.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }
}

package com.outertiers.tiertagger.fabric;

import com.outertiers.tiertagger.common.TierTaggerCore;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;

/**
 * Asynchronously fetches Minecraft head avatars by username and registers
 * them as dynamic textures so the screens can render a real face for offline
 * players (i.e. anyone not currently in the local player tab list).
 *
 * Implementation notes:
 *  - Uses {@code https://mc-heads.net/avatar/<name>/64.png} which already
 *    bakes in the second-layer overlay, so a flat 64x64 PNG can be drawn
 *    straight to the screen — no special skin shader is needed.
 *  - All HTTP work happens off the render thread; texture upload is then
 *    bounced back onto the client tick executor (mandatory for GL).
 *  - Successful fetches are cached for the lifetime of the process; failed
 *    fetches are also remembered so we don't hammer the service.
 */
public final class SkinFetcher {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .executor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "TierTagger-Skin");
                t.setDaemon(true);
                return t;
            }))
            .build();

    /** name (lowercase) → registered identifier, or {@link #FAILED} marker. */
    private static final ConcurrentMap<String, Identifier> READY = new ConcurrentHashMap<>();
    /** names with an in-flight HTTP request. */
    private static final java.util.Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    /** Sentinel value marking a permanent failure (404 / network down). */
    private static final Identifier FAILED;
    static {
        Identifier tmp;
        try { tmp = Identifier.of("tiertagger", "skins/__failed__"); }
        catch (Throwable t) { tmp = Identifier.ofVanilla("textures/entity/player/wide/steve.png"); }
        FAILED = tmp;
    }

    private SkinFetcher() {}

    /**
     * Returns the dynamic head texture for {@code name} when it has been
     * downloaded, or empty while a fetch is queued / in flight. The first
     * call for a name kicks off the download; subsequent calls just observe
     * the cache.
     */
    public static Optional<Identifier> headFor(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String key = name.toLowerCase(Locale.ROOT);
        Identifier id = READY.get(key);
        if (id == FAILED) return Optional.empty();
        if (id != null) return Optional.of(id);
        request(key);
        return Optional.empty();
    }

    private static void request(String key) {
        if (!IN_FLIGHT.add(key)) return;
        CompletableFuture.runAsync(() -> {
            try {
                URI uri = URI.create("https://mc-heads.net/avatar/" + key + "/64.png");
                HttpRequest req = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", "TierTagger/" + TierTaggerCore.MOD_VERSION)
                        .GET().build();
                HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() / 100 != 2 || res.body() == null || res.body().length == 0) {
                    READY.put(key, FAILED);
                    IN_FLIGHT.remove(key);
                    return;
                }
                byte[] bytes = res.body();
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc == null) {
                    READY.put(key, FAILED);
                    IN_FLIGHT.remove(key);
                    return;
                }
                mc.execute(() -> {
                    try {
                        NativeImage img = NativeImage.read(new ByteArrayInputStream(bytes));
                        String label = "tiertagger/skin/" + safeId(key);
                        NativeImageBackedTexture tex = com.outertiers.tiertagger.fabric.compat.Compat
                                .makeNativeImageTex(img, label);
                        if (tex == null) {
                            READY.put(key, FAILED);
                            return;
                        }
                        // CRITICAL (MC 1.21.6+): the (Supplier<String>, NativeImage)
                        // constructor stores the image but does NOT allocate a GPU
                        // texture handle. Without these explicit createTexture +
                        // upload calls, registerTexture binds an empty handle and
                        // every drawn skin face renders as nothing. This is THE
                        // reason mc-heads avatars were invisible in profile/compare.
                        com.outertiers.tiertagger.fabric.compat.Compat.initGpuTexture(tex, label);
                        Identifier id = Identifier.of("tiertagger", "skins/" + safeId(key));
                        mc.getTextureManager().registerTexture(id, tex);
                        READY.put(key, id);
                    } catch (Throwable t) {
                        TierTaggerCore.LOGGER.warn("[TierTagger] skin upload failed for {}: {}", key, t.toString());
                        READY.put(key, FAILED);
                    } finally {
                        IN_FLIGHT.remove(key);
                    }
                });
            } catch (Throwable t) {
                READY.put(key, FAILED);
                IN_FLIGHT.remove(key);
            }
        });
    }

    /** Identifier paths only allow a-z0-9/_.-; sanitise just in case. */
    private static String safeId(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-') sb.append(c);
            else sb.append('_');
        }
        return sb.length() == 0 ? "anon" : sb.toString();
    }
}

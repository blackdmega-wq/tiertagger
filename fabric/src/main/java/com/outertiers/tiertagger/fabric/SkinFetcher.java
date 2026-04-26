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
 * Asynchronously fetches Minecraft player skin renders by username and
 * registers them as dynamic textures so the screens can render a real
 * player image for offline players (i.e. anyone not currently in the
 * local player tab list).
 *
 * Implementation notes:
 *  - Uses {@code https://mc-heads.net/body/<name>/<size>.png} which returns
 *    a 2D full-body render (1:2 aspect) — the same look as the OuterTiers
 *    website player previews. The actual image dimensions are read off the
 *    decoded {@link NativeImage} and cached alongside the texture so
 *    callers can scale the image with the correct aspect ratio (without
 *    stretching the player into a square).
 *  - All HTTP work happens off the render thread; texture upload is then
 *    bounced back onto the client tick executor (mandatory for GL).
 *  - Successful fetches are cached for the lifetime of the process; failed
 *    fetches are also remembered so we don't hammer the service.
 */
public final class SkinFetcher {

    /** Decoded body-render size requested from mc-heads.net. */
    private static final int BODY_REQUEST_SIZE = 256;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .executor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "TierTagger-Skin");
                t.setDaemon(true);
                return t;
            }))
            .build();

    /** Immutable value object describing a fetched skin texture. */
    public static final class Skin {
        public final Identifier id;
        public final int width;
        public final int height;
        Skin(Identifier id, int width, int height) {
            this.id = id; this.width = width; this.height = height;
        }
    }

    /** name (lowercase) → registered Skin entry, or {@link #FAILED} marker. */
    private static final ConcurrentMap<String, Skin> READY = new ConcurrentHashMap<>();
    /** names with an in-flight HTTP request. */
    private static final java.util.Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    /** Sentinel value marking a permanent failure (404 / network down). */
    private static final Skin FAILED;
    static {
        Identifier tmp;
        try { tmp = Identifier.of("tiertagger", "skins/__failed__"); }
        catch (Throwable t) { tmp = Identifier.ofVanilla("textures/entity/player/wide/steve.png"); }
        FAILED = new Skin(tmp, 0, 0);
    }

    private SkinFetcher() {}

    /**
     * Returns the dynamic body texture for {@code name} when it has been
     * downloaded, or empty while a fetch is queued / in flight. The first
     * call for a name kicks off the download; subsequent calls just observe
     * the cache.
     */
    public static Optional<Skin> skinFor(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String key = name.toLowerCase(Locale.ROOT);
        Skin s = READY.get(key);
        if (s == FAILED) return Optional.empty();
        if (s != null) return Optional.of(s);
        request(key);
        return Optional.empty();
    }

    /**
     * Backwards-compatible accessor that returns just the texture identifier
     * (no dimensions). Kept so older call-sites keep compiling, but new code
     * should prefer {@link #skinFor(String)} which exposes the natural
     * width/height for proper aspect-ratio rendering.
     */
    public static Optional<Identifier> headFor(String name) {
        return skinFor(name).map(s -> s.id);
    }

    private static void request(String key) {
        if (!IN_FLIGHT.add(key)) return;
        CompletableFuture.runAsync(() -> {
            try {
                // Body endpoint = full-body 2D render with the hat overlay
                // baked in. This is what the user wants instead of the old
                // square head-only avatar that looked oversized in the
                // profile / compare screens.
                URI uri = URI.create("https://mc-heads.net/body/" + key + "/" + BODY_REQUEST_SIZE + ".png");
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
                        int iw = img.getWidth();
                        int ih = img.getHeight();
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
                        READY.put(key, new Skin(id, iw, ih));
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

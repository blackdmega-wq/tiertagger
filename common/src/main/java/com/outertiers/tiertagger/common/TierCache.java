package com.outertiers.tiertagger.common;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * In-memory cache of player tier data fetched from the OuterTiers REST API.
 * Lookups are non-blocking — they return whatever is cached and trigger a background fetch
 * if the entry is stale or missing.
 */
public class TierCache {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final ScheduledExecutorService EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "TierTagger-Fetcher");
        t.setDaemon(true);
        return t;
    });

    public static class Entry {
        public final Map<String, String> tiers;   // gamemode -> tier (e.g. "HT3")
        public final String  peakTier;
        public final String  region;
        public final long    fetchedAt;
        public final boolean missing;             // true => 404 / unknown player

        public Entry(Map<String,String> t, String peak, String region, long ts, boolean missing) {
            this.tiers = t; this.peakTier = peak; this.region = region;
            this.fetchedAt = ts; this.missing = missing;
        }
    }

    private final TierConfig cfg;
    private final ConcurrentHashMap<String, Entry> entries  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long>  inflight = new ConcurrentHashMap<>();

    public TierCache(TierConfig cfg) { this.cfg = cfg; }

    public Optional<Entry> peek(String username) {
        if (username == null || username.isBlank()) return Optional.empty();
        Entry e = entries.get(username.toLowerCase(Locale.ROOT));
        long ageSec = e == null ? Long.MAX_VALUE : (System.currentTimeMillis() - e.fetchedAt) / 1000L;
        if (e == null || ageSec > cfg.cacheTtlSeconds) {
            requestAsync(username);
        }
        return Optional.ofNullable(e);
    }

    public void invalidate() { entries.clear(); inflight.clear(); }

    public void invalidatePlayer(String username) {
        if (username == null) return;
        String key = username.toLowerCase(Locale.ROOT);
        entries.remove(key);
        inflight.remove(key);
    }

    public int size() { return entries.size(); }

    private void requestAsync(String username) {
        String key = username.toLowerCase(Locale.ROOT);
        long now   = System.currentTimeMillis();
        Long last  = inflight.get(key);
        if (last != null && now - last < 10_000) return; // throttle
        inflight.put(key, now);
        EXEC.submit(() -> fetch(username));
    }

    private void fetch(String username) {
        String key = username.toLowerCase(Locale.ROOT);
        try {
            String url = cfg.apiBase.replaceAll("/+$", "") + "/api/players/" + java.net.URLEncoder.encode(username, java.nio.charset.StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "TierTagger/1.0 (Minecraft mod)")
                    .GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 404) {
                entries.put(key, new Entry(Map.of(), null, null, System.currentTimeMillis(), true));
                return;
            }
            if (res.statusCode() / 100 != 2) {
                TierTaggerCore.LOGGER.debug("[TierTagger] HTTP {} for {}", res.statusCode(), username);
                return;
            }
            JsonObject root = JsonParser.parseString(res.body()).getAsJsonObject();
            JsonObject player = root.has("player") ? root.getAsJsonObject("player")
                              : root.has("data")   ? root.getAsJsonObject("data")
                              : root;

            Map<String, String> tiers = new java.util.HashMap<>();
            JsonElement tEl = player.get("tiers");
            if (tEl != null && tEl.isJsonObject()) {
                JsonObject tObj = tEl.getAsJsonObject();
                for (String mode : TierConfig.GAMEMODES) {
                    if ("overall".equals(mode)) continue;
                    JsonElement v = tObj.get(mode);
                    if (v != null && !v.isJsonNull()) {
                        String s = v.getAsString();
                        if (s != null && !s.isBlank() && !"-".equals(s)) tiers.put(mode, s.toUpperCase(Locale.ROOT));
                    }
                }
            }
            String peak   = optStr(player, "peakTier");
            String region = optStr(player, "region");
            entries.put(key, new Entry(tiers, peak, region, System.currentTimeMillis(), tiers.isEmpty() && peak == null));
        } catch (Exception e) {
            TierTaggerCore.LOGGER.debug("[TierTagger] fetch failed for {}: {}", username, e.getMessage());
        } finally {
            inflight.remove(key);
        }
    }

    private static String optStr(JsonObject o, String k) {
        JsonElement v = o.get(k);
        return (v == null || v.isJsonNull()) ? null : v.getAsString();
    }
}

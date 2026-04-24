package com.outertiers.tiertagger.common;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-memory multi-service cache. {@link #peek(String)} is non-blocking — it
 * returns whatever data is already loaded and triggers a background fetch
 * (per service, in parallel) if any entry is stale.
 *
 * Storage is keyed by the lowercase username so the existing tab-list /
 * nametag mixins, which only have a username, can still hit the cache.
 *
 * Backwards-compatibility shim: {@link Entry} mirrors the old single-service
 * shape and is derived on demand from the highest-tier across the user's
 * configured "primary" service. This keeps existing call sites compiling
 * while we migrate the screens to the multi-service API.
 */
public class TierCache {

    /** Legacy single-service view, kept so existing helpers in
     *  {@link TierTaggerCore#chooseTier(Entry)} still work. */
    public static class Entry {
        public final Map<String, String> tiers;   // gamemode -> "HT3"
        public final String  peakTier;
        public final String  region;
        public final long    fetchedAt;
        public final boolean missing;

        public Entry(Map<String,String> t, String peak, String region, long ts, boolean missing) {
            this.tiers = t; this.peakTier = peak; this.region = region;
            this.fetchedAt = ts; this.missing = missing;
        }

        public static Entry fromService(ServiceData d) {
            if (d == null) return new Entry(Map.of(), null, null, 0L, true);
            java.util.LinkedHashMap<String,String> tiers = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Ranking> e : d.rankings.entrySet()) {
                Ranking r = e.getValue();
                if (r != null && r.tierLevel > 0) tiers.put(e.getKey(), r.label());
            }
            Ranking best = d.highest();
            String peak = null;
            if (best != null) peak = best.peakLabel();
            return new Entry(tiers, peak, d.region, d.fetchedAt, d.missing);
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final ExecutorService EXEC = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "TierTagger-Fetcher");
        t.setDaemon(true);
        return t;
    });

    private final TierConfig cfg;
    private final ConcurrentHashMap<String, PlayerData> entries  = new ConcurrentHashMap<>();
    /** Per-(player,service) inflight markers to throttle repeated requests. */
    private final ConcurrentHashMap<String, Long> inflight = new ConcurrentHashMap<>();

    public TierCache(TierConfig cfg) { this.cfg = cfg; }

    // ---------- public api ----------

    /** Returns the multi-service player snapshot, kicking off background fetches as needed. */
    public Optional<PlayerData> peekData(String username) {
        if (username == null || username.isBlank()) return Optional.empty();
        String key = username.toLowerCase(Locale.ROOT);
        PlayerData data = entries.get(key);
        long ageSec = data == null ? Long.MAX_VALUE
                : (System.currentTimeMillis() - data.fetchedAt) / 1000L;
        if (data == null) {
            data = new PlayerData(username, MojangResolver.peek(username).orElse(null));
            entries.put(key, data);
            requestAllAsync(username, data);
        } else {
            // Refresh any service entries past TTL.
            for (TierService s : TierService.values()) {
                ServiceData sd = data.services.get(s);
                long sAge = sd == null || sd.fetchedAt == 0L ? Long.MAX_VALUE
                        : (System.currentTimeMillis() - sd.fetchedAt) / 1000L;
                if (sAge > cfg.cacheTtlSeconds) requestServiceAsync(username, data, s);
            }
            if (data.uuidNoDash == null && ageSec > 5) {
                // We may have learned the UUID via PlayerListEntry since first peek.
                String uuid = MojangResolver.peek(username).orElse(null);
                if (uuid != null) requestAllAsync(username, replaceUuid(key, data, uuid));
            }
        }
        return Optional.of(data);
    }

    /** Convenience for the existing badge mixins / chooseTier(...) helper. */
    public Optional<Entry> peek(String username) {
        Optional<PlayerData> opt = peekData(username);
        if (opt.isEmpty()) return Optional.empty();
        TierService primary = TierService.byIdOr(cfg.primaryService, TierService.OUTERTIERS);
        ServiceData sd = opt.get().get(primary);
        return Optional.of(Entry.fromService(sd));
    }

    public void invalidate() { entries.clear(); inflight.clear(); }

    public void invalidatePlayer(String username) {
        if (username == null) return;
        String key = username.toLowerCase(Locale.ROOT);
        entries.remove(key);
        // Drop all inflight markers for this player.
        inflight.keySet().removeIf(k -> k.startsWith(key + "|"));
    }

    public int size() { return entries.size(); }

    // ---------- fetch orchestration ----------

    private PlayerData replaceUuid(String key, PlayerData old, String newUuid) {
        PlayerData fresh = new PlayerData(old.username, newUuid);
        // copy over any already-resolved service data
        for (TierService s : TierService.values()) {
            ServiceData sd = old.services.get(s);
            if (sd != null && sd.fetchedAt != 0L) fresh.services.put(s, sd);
        }
        entries.put(key, fresh);
        return fresh;
    }

    private void requestAllAsync(String username, PlayerData data) {
        for (TierService s : TierService.values()) requestServiceAsync(username, data, s);
    }

    private void requestServiceAsync(String username, PlayerData data, TierService service) {
        String inflightKey = username.toLowerCase(Locale.ROOT) + "|" + service.id;
        long now = System.currentTimeMillis();
        Long last = inflight.get(inflightKey);
        if (last != null && now - last < 10_000) return;
        inflight.put(inflightKey, now);
        EXEC.submit(() -> {
            try { fetchOne(username, data, service); }
            finally { inflight.remove(inflightKey); }
        });
    }

    private void fetchOne(String username, PlayerData data, TierService service) {
        String key = username.toLowerCase(Locale.ROOT);
        try {
            String urlSuffix;
            if (service.lookup == TierService.Lookup.USERNAME) {
                urlSuffix = URLEncoder.encode(username, StandardCharsets.UTF_8);
            } else {
                String uuid = data.uuidNoDash;
                if (uuid == null) uuid = MojangResolver.resolveBlocking(username);
                if (uuid == null) {
                    // Mark this service as missing so we stop spamming; allow retry on next peek
                    data.services.put(service, ServiceData.missing(service));
                    return;
                }
                if (data.uuidNoDash == null) {
                    data = replaceUuid(key, data, uuid);
                }
                urlSuffix = uuid;
            }

            String url = service.apiBase + urlSuffix;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "TierTagger/1.4 (Minecraft mod)")
                    .GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            int code = res.statusCode();
            if (code == 404) {
                data.services.put(service, ServiceData.missing(service));
                return;
            }
            if (code / 100 != 2 || res.body() == null || res.body().isBlank()) {
                TierTaggerCore.LOGGER.debug("[TierTagger] {} HTTP {} for {}", service.id, code, username);
                // Treat as missing for now (don't block other services or future retries).
                data.services.put(service, ServiceData.missing(service));
                return;
            }

            ServiceData parsed = service == TierService.OUTERTIERS
                    ? parseOuterTiers(service, res.body())
                    : parseStandard(service, res.body());
            data.services.put(service, parsed);
        } catch (Exception e) {
            TierTaggerCore.LOGGER.debug("[TierTagger] {} fetch failed for {}: {}",
                    service.id, username, e.getMessage());
            // mark missing so the loading spinner doesn't hang forever
            data.services.put(service, ServiceData.missing(service));
        }
    }

    // ---------- parsers ----------

    /**
     * Parses the MCTiers / SubTiers / PvPTiers JSON shape:
     *   { uuid, name, region, points, overall,
     *     rankings: { mode: { tier, pos, peak_tier, peak_pos, retired, attained } } }
     */
    private static ServiceData parseStandard(TierService service, String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            String region = optStr(root, "region");
            int points    = optInt(root, "points", 0);
            int overall   = optInt(root, "overall", 0);
            java.util.LinkedHashMap<String, Ranking> rankings = new java.util.LinkedHashMap<>();
            JsonElement rEl = root.get("rankings");
            if (rEl != null && rEl.isJsonObject()) {
                JsonObject r = rEl.getAsJsonObject();
                for (String mode : service.modes) {
                    JsonElement m = r.get(mode);
                    if (m == null || !m.isJsonObject()) continue;
                    JsonObject mo = m.getAsJsonObject();
                    int tier = optInt(mo, "tier", 0);
                    int pos  = optInt(mo, "pos",  1);
                    int peakT= optInt(mo, "peak_tier", tier);
                    int peakP= optInt(mo, "peak_pos",  pos);
                    boolean retired = optBool(mo, "retired", false);
                    long attained = optLong(mo, "attained", 0L);
                    int posInTier = optInt(mo, "pos_in_tier", 0);
                    rankings.put(mode, new Ranking(tier, pos == 0, peakT, peakP == 0,
                            retired, attained, posInTier));
                }
            }
            return new ServiceData(service, rankings, region, points, overall,
                    System.currentTimeMillis(), false);
        } catch (Exception e) {
            TierTaggerCore.LOGGER.debug("[TierTagger] {} parse failed: {}", service.id, e.getMessage());
            return ServiceData.missing(service);
        }
    }

    /**
     * Parses the OuterTiers shape:
     *   { player|data: { rawTiers: { mode: "HT3", peak: "HT2" }, tiers: { mode: "T3" },
     *                    region, peakTier } }
     */
    private static ServiceData parseOuterTiers(TierService service, String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject player = root.has("player") && root.get("player").isJsonObject() ? root.getAsJsonObject("player")
                              : root.has("data")   && root.get("data").isJsonObject()   ? root.getAsJsonObject("data")
                              : root;

            String region = optStr(player, "region");
            int points    = optInt(player, "points", 0);
            int overall   = optInt(player, "overall", 0);

            JsonObject raw  = optObj(player, "rawTiers");
            JsonObject tArr = optObj(player, "tiers");
            String topPeak  = optStr(player, "peakTier");

            java.util.LinkedHashMap<String, Ranking> rankings = new java.util.LinkedHashMap<>();
            for (String mode : service.modes) {
                String s = optStr(raw, mode);
                if (s == null) s = optStr(tArr, mode);
                Ranking r = parseHtLt(s);
                if (r != null) rankings.put(mode, r);
            }
            // Apply the global peak tier as the per-mode peak when the per-mode peak is unknown
            // and the player has at least one ranked mode (this is how OuterTiers mirrors it).
            if (topPeak != null && !topPeak.isBlank() && !"-".equals(topPeak)) {
                Ranking peak = parseHtLt(topPeak);
                if (peak != null) {
                    java.util.LinkedHashMap<String, Ranking> withPeak = new java.util.LinkedHashMap<>();
                    for (Map.Entry<String, Ranking> e : rankings.entrySet()) {
                        Ranking cur = e.getValue();
                        if (cur.peakLevel <= 0 || cur.score() < peak.score()) {
                            withPeak.put(e.getKey(), new Ranking(cur.tierLevel, cur.high,
                                    peak.tierLevel, peak.high, cur.retired, cur.attained, cur.posInTier));
                        } else {
                            withPeak.put(e.getKey(), cur);
                        }
                    }
                    rankings = withPeak;
                }
            }
            return new ServiceData(service, rankings, region, points, overall,
                    System.currentTimeMillis(), false);
        } catch (Exception e) {
            TierTaggerCore.LOGGER.debug("[TierTagger] outertiers parse failed: {}", e.getMessage());
            return ServiceData.missing(service);
        }
    }

    /** Parses "HT3" / "LT4" / "T3" into a Ranking. Returns null on garbage. */
    private static Ranking parseHtLt(String s) {
        if (s == null) return null;
        String u = s.trim().toUpperCase(Locale.ROOT);
        if (u.isEmpty() || "-".equals(u)) return null;
        boolean high = true;
        int idx = 0;
        if (u.startsWith("HT")) { high = true;  idx = 2; }
        else if (u.startsWith("LT")) { high = false; idx = 2; }
        else if (u.startsWith("T"))  { high = true;  idx = 1; }
        else return null;
        if (idx >= u.length()) return null;
        char c = u.charAt(idx);
        if (c < '1' || c > '5') return null;
        return Ranking.simple(c - '0', high);
    }

    // ---------- json helpers ----------

    private static String optStr(JsonObject o, String k) {
        if (o == null) return null;
        JsonElement v = o.get(k);
        if (v == null || v.isJsonNull()) return null;
        try { String s = v.getAsString(); return (s == null || s.isBlank()) ? null : s; }
        catch (Exception ignored) { return null; }
    }

    private static int optInt(JsonObject o, String k, int dflt) {
        if (o == null) return dflt;
        JsonElement v = o.get(k);
        if (v == null || v.isJsonNull()) return dflt;
        try { return v.getAsInt(); } catch (Exception ignored) { return dflt; }
    }

    private static long optLong(JsonObject o, String k, long dflt) {
        if (o == null) return dflt;
        JsonElement v = o.get(k);
        if (v == null || v.isJsonNull()) return dflt;
        try { return v.getAsLong(); } catch (Exception ignored) { return dflt; }
    }

    private static boolean optBool(JsonObject o, String k, boolean dflt) {
        if (o == null) return dflt;
        JsonElement v = o.get(k);
        if (v == null || v.isJsonNull()) return dflt;
        try { return v.getAsBoolean(); } catch (Exception ignored) { return dflt; }
    }

    private static JsonObject optObj(JsonObject o, String k) {
        if (o == null) return null;
        JsonElement v = o.get(k);
        return (v != null && v.isJsonObject()) ? v.getAsJsonObject() : null;
    }
}

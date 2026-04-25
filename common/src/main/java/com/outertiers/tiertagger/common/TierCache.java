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
 * In-memory multi-service cache. {@link #peekData(String)} is non-blocking —
 * it returns whatever data is already loaded and triggers a background fetch
 * (per service, in parallel) if any entry is stale.
 *
 * <p><b>Thread safety:</b> Each background thread writes to the same
 * {@link PlayerData} object that lives in {@link #entries}. Because
 * {@code uuidNoDash} is volatile and each service writes to a distinct
 * {@link java.util.EnumMap} slot (distinct {@code enum} ordinal), the
 * concurrent writes are safe in practice. Crucially, we never replace the
 * {@code PlayerData} object once it is in the map — the old "replaceUuid"
 * approach caused a race where concurrent threads would overwrite each
 * other's results.</p>
 */
public class TierCache {

    /**
     * Legacy single-service view, kept so existing helpers in
     * {@link TierTaggerCore#chooseTier(Entry)} still compile.
     */
    public static class Entry {
        public final Map<String, String> tiers;
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
    // 8 threads = 4 services × 2 simultaneous players (the worst case is the
    // /tiertagger compare screen, which kicks off both players in parallel).
    private static final ExecutorService EXEC = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "TierTagger-Fetcher");
        t.setDaemon(true);
        return t;
    });

    private final TierConfig cfg;
    private final ConcurrentHashMap<String, PlayerData> entries  = new ConcurrentHashMap<>();
    /** Per-(player,service) in-flight markers to throttle repeated requests. */
    private final ConcurrentHashMap<String, Long> inflight = new ConcurrentHashMap<>();

    public TierCache(TierConfig cfg) { this.cfg = cfg; }

    // ---------- public api ----------

    /** Returns the multi-service player snapshot, kicking off background fetches as needed. */
    public Optional<PlayerData> peekData(String username) {
        if (username == null || username.isBlank()) return Optional.empty();
        String key = username.toLowerCase(Locale.ROOT);

        // Pre-populate UUID cache from MojangResolver if already known.
        String knownUuid = MojangResolver.peek(username).orElse(null);

        PlayerData data = entries.get(key);
        if (data == null) {
            PlayerData fresh = new PlayerData(username, knownUuid);
            PlayerData existing = entries.putIfAbsent(key, fresh);
            data = existing != null ? existing : fresh;
            requestAllAsync(username, data);
        } else {
            // Lazily backfill UUID if we just learned it.
            if (data.uuidNoDash == null && knownUuid != null) {
                data.uuidNoDash = knownUuid;
            }
            // Refresh any service entries past TTL.
            for (TierService s : TierService.values()) {
                ServiceData sd = data.services.get(s);
                long sAge = sd == null || sd.fetchedAt == 0L ? Long.MAX_VALUE
                        : (System.currentTimeMillis() - sd.fetchedAt) / 1000L;
                if (sAge > cfg.cacheTtlSeconds) requestServiceAsync(username, data, s);
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
        inflight.keySet().removeIf(k -> k.startsWith(key + "|"));
    }

    public int size() { return entries.size(); }

    // ---------- doctor / one-shot diagnostic ----------

    /** Per-service result of a single synchronous fetch attempt. */
    public static final class ServiceReport {
        public final TierService service;
        public final String      url;
        /** HTTP status code, or -1 if the request threw before getting a response. */
        public final int         httpStatus;
        /** Best parsed tier label like "HT3", or {@code null} when unranked / not available. */
        public final String      parsedTier;
        /** True when the upstream returned 404 (player definitively not on this list). */
        public final boolean     missing;
        /** Human-readable failure reason; {@code null} on success. */
        public final String      errorMessage;
        public final long        elapsedMs;

        public ServiceReport(TierService service, String url, int httpStatus,
                             String parsedTier, boolean missing,
                             String errorMessage, long elapsedMs) {
            this.service      = service;
            this.url          = url;
            this.httpStatus   = httpStatus;
            this.parsedTier   = parsedTier;
            this.missing      = missing;
            this.errorMessage = errorMessage;
            this.elapsedMs    = elapsedMs;
        }
    }

    /** Aggregate result of {@link #diagnoseBlocking(String)}. */
    public static final class DoctorReport {
        public final String username;
        /** Resolved 32-hex UUID, or {@code null} if resolution failed. */
        public final String uuidNoDash;
        /** Where the UUID came from: {@code "cache"} / {@code "resolved"} / {@code null} on failure. */
        public final String uuidSource;
        public final java.util.EnumMap<TierService, ServiceReport> services =
                new java.util.EnumMap<>(TierService.class);

        public DoctorReport(String username, String uuidNoDash, String uuidSource) {
            this.username   = username;
            this.uuidNoDash = uuidNoDash;
            this.uuidSource = uuidSource;
        }
    }

    /**
     * Synchronous one-shot diagnosis. Resolves the player's UUID and then
     * issues a single request to every {@link TierService}, bypassing the
     * background fetch pool, the in-flight throttle, and the TTL cache.
     *
     * <p>This is intended to be called from a dedicated diagnostic thread
     * (e.g. {@code /tiertagger doctor}); it WILL block on network I/O.</p>
     */
    public DoctorReport diagnoseBlocking(String username) {
        String safe = username == null ? "" : username.trim();
        String uuid = MojangResolver.peek(safe).orElse(null);
        String src  = uuid != null ? "cache" : null;
        if (uuid == null && !safe.isEmpty()) {
            uuid = MojangResolver.resolveBlocking(safe);
            if (uuid != null) src = "resolved";
        }
        DoctorReport report = new DoctorReport(safe, uuid, src);
        for (TierService s : TierService.values()) {
            report.services.put(s, fetchOneSync(safe, uuid, s));
        }
        return report;
    }

    private static ServiceReport fetchOneSync(String username, String uuid, TierService service) {
        long started = System.currentTimeMillis();
        String url = service.apiBase;
        try {
            String urlSuffix;
            if (service.lookup == TierService.Lookup.USERNAME) {
                if (username.isEmpty()) {
                    return new ServiceReport(service, url, -1, null, false,
                            "no username", System.currentTimeMillis() - started);
                }
                urlSuffix = URLEncoder.encode(username, StandardCharsets.UTF_8);
            } else {
                if (uuid == null) {
                    return new ServiceReport(service, url, -1, null, false,
                            "no UUID resolved", System.currentTimeMillis() - started);
                }
                urlSuffix = uuid;
            }
            url = service.apiBase + urlSuffix;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "TierTagger/" + TierTaggerCore.MOD_VERSION + " (+https://github.com/blackdmega-wq/tiertagger)")
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            int code = res.statusCode();
            long elapsed = System.currentTimeMillis() - started;
            if (code == 404) {
                return new ServiceReport(service, url, 404, null, true, null, elapsed);
            }
            if (code / 100 != 2 || res.body() == null || res.body().isBlank()) {
                return new ServiceReport(service, url, code, null, false,
                        "non-2xx or empty body", elapsed);
            }
            ServiceData parsed = service == TierService.OUTERTIERS
                    ? parseOuterTiers(service, res.body())
                    : parseStandard(service, res.body());
            if (parsed.missing) {
                return new ServiceReport(service, url, code, null, true,
                        "parser returned missing", elapsed);
            }
            Ranking best = parsed.highest();
            String label = best == null ? null : best.label();
            return new ServiceReport(service, url, code, label, false, null, elapsed);
        } catch (Exception e) {
            String msg = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            return new ServiceReport(service, url, -1, null, false, msg,
                    System.currentTimeMillis() - started);
        }
    }

    // ---------- fetch orchestration ----------

    private void requestAllAsync(String username, PlayerData data) {
        for (TierService s : TierService.values()) requestServiceAsync(username, data, s);
    }

    private void requestServiceAsync(String username, PlayerData data, TierService service) {
        String inflightKey = username.toLowerCase(Locale.ROOT) + "|" + service.id;
        long now = System.currentTimeMillis();
        Long last = inflight.get(inflightKey);
        if (last != null && now - last < 30_000) return;
        inflight.put(inflightKey, now);
        EXEC.submit(() -> {
            try { fetchOne(username, data, service); }
            finally { inflight.remove(inflightKey); }
        });
    }

    /**
     * Fetches one service for one player. Always writes results back to the
     * passed {@code data} object — which is the live entry in {@link #entries}.
     * We never replace the {@code PlayerData} object in the map, so there is
     * no risk of concurrent threads clobbering each other's writes.
     */
    private void fetchOne(String username, PlayerData data, TierService service) {
        try {
            String urlSuffix;
            if (service.lookup == TierService.Lookup.USERNAME) {
                urlSuffix = URLEncoder.encode(username, StandardCharsets.UTF_8);
            } else {
                // UUID-based lookup — resolve lazily and store on the shared object.
                String uuid = data.uuidNoDash;
                if (uuid == null) uuid = MojangResolver.resolveBlocking(username);
                if (uuid == null) {
                    data.services.put(service, ServiceData.missing(service));
                    return;
                }
                // Persist resolved UUID so other concurrent threads don't re-resolve.
                if (data.uuidNoDash == null) data.uuidNoDash = uuid;
                urlSuffix = uuid;
            }

            String url = service.apiBase + urlSuffix;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "TierTagger/" + TierTaggerCore.MOD_VERSION + " (+https://github.com/blackdmega-wq/tiertagger)")
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            int code = res.statusCode();
            if (code == 404) {
                data.services.put(service, ServiceData.missing(service));
                return;
            }
            if (code / 100 != 2 || res.body() == null || res.body().isBlank()) {
                // Transient server error — don't permanently mark missing; retry after 30 s via inflight
                TierTaggerCore.LOGGER.info("[TierTagger] {} HTTP {} for {} — will retry", service.id, code, username);
                return;
            }

            ServiceData parsed = service == TierService.OUTERTIERS
                    ? parseOuterTiers(service, res.body())
                    : parseStandard(service, res.body());
            data.services.put(service, parsed);
        } catch (Exception e) {
            // Network / timeout error — don't permanently mark missing; retry after 30 s via inflight
            TierTaggerCore.LOGGER.info("[TierTagger] {} fetch failed for {}: {} — will retry",
                    service.id, username, e.getMessage());
        }
    }

    // ---------- parsers ----------

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
            TierTaggerCore.LOGGER.warn("[TierTagger] {} parse failed: {}", service.id, e.getMessage());
            return ServiceData.missing(service);
        }
    }

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
            TierTaggerCore.LOGGER.warn("[TierTagger] outertiers parse failed: {}", e.getMessage());
            return ServiceData.missing(service);
        }
    }

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

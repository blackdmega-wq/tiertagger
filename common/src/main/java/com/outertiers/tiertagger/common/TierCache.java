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
            .connectTimeout(Duration.ofSeconds(4))
            .build();
    // 16 threads — 4 services × 2 players for /compare PLUS room for tab-list
    // pre-fetch of nearby player slots. The previous 8-thread cap meant the
    // compare screen filled the queue and tab-list lookups for other players
    // had to wait until one of those 8 fetches finished, making "loading…"
    // labels in the tab list visibly stick around for several seconds.
    private static final ExecutorService EXEC = Executors.newFixedThreadPool(32, r -> {
        Thread t = new Thread(r, "TierTagger-Fetcher");
        t.setDaemon(true);
        return t;
    });

    /**
     * Per-MC-version aliases for upstream gamemode JSON keys.  Some tier-list
     * APIs (notably PvPTiers) have shipped the same gamemode under more than
     * one key over time — e.g. Netherite Pot has been seen as
     * {@code "nethpot"}, {@code "neth_pot"}, {@code "netheritepot"} and
     * {@code "netherite_pot"} in different snapshots.  We map our canonical
     * mode id to every alias the parser should also accept so a single
     * upstream rename doesn't silently drop the player's tier from the
     * profile / compare screens.
     */
    private static final java.util.Map<String, String[]> MODE_ALIASES = java.util.Map.of(
            "nethpot",  new String[] { "nethpot", "neth_pot", "netheritepot", "netherite_pot" },
            "nethop",   new String[] { "nethop", "neth_op", "netherite_op", "netheriteop" },
            "smp",      new String[] { "smp", "mace_smp" },
            "crystal",  new String[] { "crystal", "crystalpvp", "crystal_pvp" },
            "ogvanilla",new String[] { "ogvanilla", "og_vanilla" },
            "og_vanilla", new String[] { "og_vanilla", "ogvanilla" },
            "dia_2v2",  new String[] { "dia_2v2", "dia2v2", "diamond_2v2" }
    );

    /**
     * First non-null JSON child found under the canonical mode key, any of its
     * registered {@link #MODE_ALIASES}, or — as a last resort — any parent key
     * that {@code equalsIgnoreCase()} matches the canonical id or any alias.
     *
     * <p>The case-insensitive parent-key sweep is what fixes the long-standing
     * "PvPTiers nethpot tier never loads" bug: PvPTiers' API has historically
     * shipped that mode under {@code "Nethpot"}, {@code "NethPot"} and lately
     * {@code "nethPot"} — Gson's {@link JsonObject#get(String)} is strictly
     * case-sensitive, so every lookup against our lowercase canonical id
     * returned {@code null} and the tier was silently dropped from the cached
     * rankings.
     */
    private static JsonElement firstAlias(JsonObject parent, String mode) {
        if (parent == null || mode == null) return null;
        String canonical = mode.toLowerCase(Locale.ROOT);
        String[] aliases = MODE_ALIASES.get(canonical);
        // 1) Direct exact-case lookups (canonical id + every registered alias).
        if (aliases == null) {
            JsonElement v = parent.get(mode);
            if (v != null && !v.isJsonNull()) return v;
        } else {
            for (String k : aliases) {
                JsonElement v = parent.get(k);
                if (v != null && !v.isJsonNull()) return v;
            }
        }
        // 2) Case-insensitive sweep over the parent's actual keys. We compare
        //    against canonical id + aliases AND a "no underscores" version so
        //    "Netherite_Pot", "netheritePot", and "NETHPOT" all match nethpot.
        for (Map.Entry<String, JsonElement> e : parent.entrySet()) {
            String k = e.getKey();
            if (k == null) continue;
            String kn = k.toLowerCase(Locale.ROOT);
            String knStripped = kn.replace("_", "");
            if (kn.equals(canonical) || knStripped.equals(canonical.replace("_", ""))) {
                if (e.getValue() != null && !e.getValue().isJsonNull()) return e.getValue();
            }
            if (aliases != null) {
                for (String a : aliases) {
                    String al = a.toLowerCase(Locale.ROOT);
                    if (kn.equals(al) || knStripped.equals(al.replace("_", ""))) {
                        if (e.getValue() != null && !e.getValue().isJsonNull()) return e.getValue();
                    }
                }
            }
        }
        return null;
    }

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
                    .timeout(Duration.ofSeconds(8))
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
        // The inflight marker is removed in `finally` after the fetch completes,
        // so this gate only kicks in when a request is genuinely still running.
        // 4 s is a healthy safety net (HTTP timeout itself is 8 s) without
        // serialising tab-list refreshes the way the previous 30 s gate did —
        // every player slot used to be stuck on "loading…" for half a minute
        // whenever a single fetch hung. v1.21.11.37 cuts the gate from 8 s
        // to 4 s so retries kick in faster when a service is slow.
        if (last != null && now - last < 4_000) return;
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
                    .timeout(Duration.ofSeconds(8))
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
            // Accept "overall" plus the common synonyms upstream services
            // use for the global leaderboard rank — keeps the #N tag in the
            // search/compare UI populated when a service drifts naming.
            int overall   = optIntAlias(root, 0, "overall", "rank", "globalRank", "global_rank", "position", "place");
            java.util.LinkedHashMap<String, Ranking> rankings = new java.util.LinkedHashMap<>();
            JsonElement rEl = root.get("rankings");
            if (rEl != null && rEl.isJsonObject()) {
                JsonObject r = rEl.getAsJsonObject();
                for (String mode : service.modes) {
                    // firstAlias() falls back through known upstream key
                    // synonyms (e.g. "nethpot" → "neth_pot" → "netherite_pot")
                    // so a single PvPTiers JSON-key rename doesn't silently
                    // drop a player's tier.
                    JsonElement m = firstAlias(r, mode);
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
            // Same alias fallback as parseStandard — OuterTiers occasionally
            // ships the global rank under "rank" or "globalRank" depending on
            // which payload version the API returns.
            int overall   = optIntAlias(player, 0, "overall", "rank", "globalRank", "global_rank", "position", "place");

            JsonObject raw  = optObj(player, "rawTiers");
            JsonObject tArr = optObj(player, "tiers");
            String topPeak  = optStr(player, "peakTier");

            java.util.LinkedHashMap<String, Ranking> rankings = new java.util.LinkedHashMap<>();
            for (String mode : service.modes) {
                // Walk the alias list so the OuterTiers parser also accepts
                // upstream key renames without losing data.
                String s = optStrAlias(raw, mode);
                if (s == null) s = optStrAlias(tArr, mode);
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

    /**
     * Alias-aware {@link #optStr} that ALSO falls through to a case-insensitive
     * sweep of the parent's actual keys.  Mirrors {@link #firstAlias} so the
     * OuterTiers parser shows the same tolerance to upstream key renames as
     * the standard parser — important because OuterTiers occasionally ships
     * legacy keys like {@code "OG_Vanilla"} or {@code "Netherite_Pot"} with
     * mixed case that strict {@link JsonObject#get(String)} would miss.
     */
    private static String optStrAlias(JsonObject o, String mode) {
        if (o == null || mode == null) return null;
        String canonical = mode.toLowerCase(Locale.ROOT);
        String[] aliases = MODE_ALIASES.get(canonical);
        // 1) Exact-case canonical / alias matches.
        if (aliases == null) {
            String s = optStr(o, mode);
            if (s != null) return s;
        } else {
            for (String k : aliases) {
                String s = optStr(o, k);
                if (s != null) return s;
            }
        }
        // 2) Case-insensitive parent-key sweep (with underscores stripped).
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            String k = e.getKey();
            if (k == null) continue;
            String kn = k.toLowerCase(Locale.ROOT);
            String knStripped = kn.replace("_", "");
            boolean matches = kn.equals(canonical) || knStripped.equals(canonical.replace("_", ""));
            if (!matches && aliases != null) {
                for (String a : aliases) {
                    String al = a.toLowerCase(Locale.ROOT);
                    if (kn.equals(al) || knStripped.equals(al.replace("_", ""))) { matches = true; break; }
                }
            }
            if (!matches) continue;
            String s = optStr(o, k);
            if (s != null) return s;
        }
        return null;
    }

    private static int optInt(JsonObject o, String k, int dflt) {
        if (o == null) return dflt;
        JsonElement v = o.get(k);
        if (v == null || v.isJsonNull()) return dflt;
        try { return v.getAsInt(); } catch (Exception ignored) { return dflt; }
    }

    /**
     * Walk through several possible key names (case-insensitive) and return
     * the first integer that is present and non-null. Used so that an upstream
     * service that calls the global-leaderboard rank "rank" / "globalRank" /
     * "position" instead of the canonical "overall" still gets surfaced as
     * `sd.overall` in the UI. Returns {@code dflt} when nothing matches.
     */
    private static int optIntAlias(JsonObject o, int dflt, String... keys) {
        if (o == null || keys == null) return dflt;
        for (String k : keys) {
            int v = optInt(o, k, Integer.MIN_VALUE);
            if (v != Integer.MIN_VALUE) return v;
        }
        // Case-insensitive second pass — handles e.g. "Overall", "GlobalRank".
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            for (String k : keys) {
                if (e.getKey().equalsIgnoreCase(k)
                        && e.getValue() != null && !e.getValue().isJsonNull()) {
                    try { return e.getValue().getAsInt(); } catch (Exception ignored) {}
                }
            }
        }
        return dflt;
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

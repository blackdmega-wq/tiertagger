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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves Minecraft usernames to dash-less UUIDs, with an in-memory cache.
 *
 * Mojang's {@code api.mojang.com/users/profiles/minecraft/<name>} is rate-limited
 * and sometimes blocked; we therefore try ashcon.app first (which mirrors Mojang
 * but with much higher per-IP limits) and fall back to Mojang on failure.
 *
 * Online players in the tab list already expose their UUID via
 * {@code PlayerListEntry#getProfile()#getId()} — those callers can short-circuit
 * by calling {@link #cache(String, String)} directly.
 */
public final class MojangResolver {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();
    /** Per-username locks so concurrent service fetches for the same player share one HTTP resolve. */
    private static final ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    /** Marker used to remember "definitely unknown" so we don't keep retrying. */
    private static final String UNKNOWN = "__UNKNOWN__";

    private MojangResolver() {}

    /** Returns the cached UUID (32 hex chars, no dashes) or empty if not yet resolved. */
    public static Optional<String> peek(String username) {
        if (username == null || username.isBlank()) return Optional.empty();
        String v = CACHE.get(username.toLowerCase(Locale.ROOT));
        if (v == null || UNKNOWN.equals(v)) return Optional.empty();
        return Optional.of(v);
    }

    /** Pre-populate the cache when we already know the UUID (e.g. from PlayerListEntry). */
    public static void cache(String username, String uuid) {
        if (username == null || uuid == null) return;
        String clean = uuid.replace("-", "").toLowerCase(Locale.ROOT);
        if (clean.length() != 32) return;
        CACHE.put(username.toLowerCase(Locale.ROOT), clean);
    }

    /** Synchronous resolve. Returns null on failure. Safe to call off the main thread. */
    public static String resolveBlocking(String username) {
        if (username == null || username.isBlank()) return null;
        String key = username.toLowerCase(Locale.ROOT);
        String cached = CACHE.get(key);
        if (cached != null) return UNKNOWN.equals(cached) ? null : cached;

        // De-dupe concurrent resolves for the same player. Without this lock,
        // every parallel service fetch (MCT, PVPT, SUBT, OT) for the same
        // freshly-looked-up player races to hit ashcon/Mojang at the same
        // moment — wasting bandwidth and adding ~5-15 s of redundant API
        // round-trips to the very first compare/profile after joining a server.
        java.util.concurrent.locks.ReentrantLock lock =
                LOCKS.computeIfAbsent(key, k -> new java.util.concurrent.locks.ReentrantLock());
        lock.lock();
        try {
            cached = CACHE.get(key);
            if (cached != null) return UNKNOWN.equals(cached) ? null : cached;
            return doResolve(key);
        } finally {
            lock.unlock();
            // Keep the lock object around — it's tiny and guarantees later
            // calls for the same name keep deduping. The CACHE result is
            // what protects us from re-running HTTP regardless.
        }
    }

    private static String doResolve(String key) {
        // 1) ashcon.app (preferred)
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.ashcon.app/mojang/v2/user/" + key))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "TierTagger/1.4 (Minecraft mod)")
                    .GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 == 2) {
                JsonObject root = JsonParser.parseString(res.body()).getAsJsonObject();
                JsonElement uuidEl = root.get("uuid");
                if (uuidEl != null && !uuidEl.isJsonNull()) {
                    String clean = uuidEl.getAsString().replace("-", "").toLowerCase(Locale.ROOT);
                    if (clean.length() == 32) { CACHE.put(key, clean); return clean; }
                }
            } else if (res.statusCode() == 404) {
                CACHE.put(key, UNKNOWN);
                return null;
            }
        } catch (Exception ignored) { /* fall through to mojang */ }

        // 2) Mojang official (rate-limited but authoritative)
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/" + key))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "TierTagger/1.4 (Minecraft mod)")
                    .GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 204 || res.statusCode() == 404) {
                CACHE.put(key, UNKNOWN);
                return null;
            }
            if (res.statusCode() / 100 == 2 && res.body() != null && !res.body().isBlank()) {
                JsonObject root = JsonParser.parseString(res.body()).getAsJsonObject();
                JsonElement idEl = root.get("id");
                if (idEl != null && !idEl.isJsonNull()) {
                    String clean = idEl.getAsString().replace("-", "").toLowerCase(Locale.ROOT);
                    if (clean.length() == 32) { CACHE.put(key, clean); return clean; }
                }
            }
        } catch (Exception ignored) { /* give up */ }

        return null;
    }

    public static void invalidate() { CACHE.clear(); }
}

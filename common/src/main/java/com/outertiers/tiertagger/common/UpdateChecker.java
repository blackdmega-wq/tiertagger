package com.outertiers.tiertagger.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Asynchronously checks GitHub Releases for a newer TierTagger build than the
 * one currently installed and exposes the result as a single {@link Status}
 * snapshot. The check runs at most once per session — call
 * {@link #checkInBackground(String, String)} from the platform entrypoint
 * after {@link TierTaggerCore#init()} has completed.
 *
 * <p>Network IO is intentionally kept simple (the JDK's built-in
 * {@link HttpClient}) so the common module doesn't pull in a heavy dependency.
 * Failures (no network, rate-limited, malformed JSON) are swallowed and
 * surfaced as {@link Status#OFFLINE} so the rest of the mod is unaffected.</p>
 *
 * <p>Per-MC-version matching: each TierTagger release tags its build with the
 * exact Minecraft version it targets (e.g. {@code 1.21.11}, {@code 1.21.12}).
 * The checker walks the release list, prefers the newest release whose tag
 * matches the running Minecraft version's {@code major.minor}, and falls back
 * to the absolute latest release if no per-MC match is found. This avoids
 * telling a 1.21.1 user to download a 1.21.11-only jar.</p>
 */
public final class UpdateChecker {

    /** GitHub API endpoint — public, no auth required (60 req/h per IP). */
    public static final String RELEASES_URL =
        "https://api.github.com/repos/blackdmega-wq/tiertagger/releases";

    /** Public download landing page shown to the user. */
    public static final String DOWNLOAD_URL =
        "https://github.com/blackdmega-wq/tiertagger/releases/latest";

    public enum Status {
        /** Check has not finished yet. */
        PENDING,
        /** A newer release was found — see {@link Result}. */
        UPDATE_AVAILABLE,
        /** The user is on the newest known release. */
        UP_TO_DATE,
        /** Network/parsing failed — never block the UI on this. */
        OFFLINE
    }

    /** Immutable snapshot returned by {@link #latest()}. */
    public static final class Result {
        public final Status status;
        public final String currentVersion;
        public final String latestVersion;   // null unless UPDATE_AVAILABLE / UP_TO_DATE
        public final String downloadUrl;     // null unless UPDATE_AVAILABLE
        public final String releaseNotesUrl; // null unless UPDATE_AVAILABLE

        Result(Status status, String currentVersion, String latestVersion,
               String downloadUrl, String releaseNotesUrl) {
            this.status = status;
            this.currentVersion = currentVersion;
            this.latestVersion = latestVersion;
            this.downloadUrl = downloadUrl;
            this.releaseNotesUrl = releaseNotesUrl;
        }
    }

    private static final AtomicReference<Result> RESULT =
        new AtomicReference<>(new Result(Status.PENDING, null, null, null, null));
    private static volatile boolean started = false;

    private UpdateChecker() {}

    /**
     * Kicks off a single background check. Subsequent calls are no-ops.
     *
     * @param currentVersion the version string of the installed mod jar
     *                       (e.g. {@code "1.21.11"} — typically the value of
     *                       the loader's {@code ModContainer.getVersion()}).
     * @param mcVersion      the running Minecraft client version
     *                       (e.g. {@code "1.21.11"} — typically the value of
     *                       the loader's minecraft mod-container version).
     */
    public static synchronized void checkInBackground(String currentVersion, String mcVersion) {
        if (started) return;
        started = true;
        RESULT.set(new Result(Status.PENDING, currentVersion, null, null, null));

        Thread t = new Thread(() -> runOnce(currentVersion, mcVersion),
            "TierTagger-UpdateChecker");
        t.setDaemon(true);
        t.start();
    }

    /** Returns the current snapshot — never {@code null}. */
    public static Result latest() {
        return RESULT.get();
    }

    /** Convenience: {@code true} iff status is {@link Status#UPDATE_AVAILABLE}. */
    public static boolean isUpdateAvailable() {
        return RESULT.get().status == Status.UPDATE_AVAILABLE;
    }

    // ------------------------------------------------------------------------
    // implementation
    // ------------------------------------------------------------------------

    private static void runOnce(String currentVersion, String mcVersion) {
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(RELEASES_URL + "?per_page=30"))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "TierTagger-Mod/" + (currentVersion == null ? "?" : currentVersion))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                TierTaggerCore.LOGGER.info("[TierTagger] update-check: HTTP {}", resp.statusCode());
                RESULT.set(new Result(Status.OFFLINE, currentVersion, null, null, null));
                return;
            }

            JsonElement root = JsonParser.parseString(resp.body());
            if (!root.isJsonArray()) {
                RESULT.set(new Result(Status.OFFLINE, currentVersion, null, null, null));
                return;
            }

            Optional<JsonObject> best = pickBestRelease(root.getAsJsonArray(), mcVersion);
            if (best.isEmpty()) {
                RESULT.set(new Result(Status.OFFLINE, currentVersion, null, null, null));
                return;
            }

            JsonObject rel = best.get();
            String latestTag = stripV(textOrNull(rel, "tag_name"));
            String htmlUrl   = textOrNull(rel, "html_url");
            String assetUrl  = pickAssetUrl(rel, mcVersion);
            if (assetUrl == null) assetUrl = htmlUrl != null ? htmlUrl : DOWNLOAD_URL;

            if (latestTag == null) {
                RESULT.set(new Result(Status.OFFLINE, currentVersion, null, null, null));
                return;
            }

            int cmp = compareVersions(latestTag, currentVersion == null ? "" : currentVersion);
            if (cmp > 0) {
                RESULT.set(new Result(Status.UPDATE_AVAILABLE, currentVersion,
                    latestTag, assetUrl, htmlUrl));
                TierTaggerCore.LOGGER.info(
                    "[TierTagger] update available: you have {}, latest is {} — {}",
                    currentVersion, latestTag, htmlUrl);
            } else {
                RESULT.set(new Result(Status.UP_TO_DATE, currentVersion,
                    latestTag, null, null));
                TierTaggerCore.LOGGER.info(
                    "[TierTagger] up to date — latest is {}", latestTag);
            }
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.info("[TierTagger] update-check failed: {}", t.toString());
            RESULT.set(new Result(Status.OFFLINE, currentVersion, null, null, null));
        }
    }

    /**
     * Picks the newest release whose tag matches the running MC version's
     * {@code major.minor.patch} prefix. Falls back to the first non-draft
     * non-prerelease release (which the GitHub API returns sorted newest-first).
     */
    private static Optional<JsonObject> pickBestRelease(JsonArray releases, String mcVersion) {
        String mcPrefix = mcMajorMinorPatch(mcVersion); // e.g. "1.21.11" → "1.21.11"
        JsonObject perMcBest = null;
        String     perMcBestTag = null;
        JsonObject anyBest   = null;

        for (JsonElement el : releases) {
            if (!el.isJsonObject()) continue;
            JsonObject rel = el.getAsJsonObject();
            if (boolOr(rel, "draft", false)) continue;
            if (boolOr(rel, "prerelease", false)) continue;

            String tag = stripV(textOrNull(rel, "tag_name"));
            if (tag == null || tag.isBlank()) continue;

            if (anyBest == null) anyBest = rel;

            if (mcPrefix != null && tag.startsWith(mcPrefix)) {
                if (perMcBest == null || compareVersions(tag, perMcBestTag) > 0) {
                    perMcBest    = rel;
                    perMcBestTag = tag;
                }
            }
        }
        if (perMcBest != null) return Optional.of(perMcBest);
        return Optional.ofNullable(anyBest);
    }

    /**
     * Picks the asset URL for the running MC version when possible, so the
     * "Download" link goes straight to the right jar. Falls back to {@code null}
     * (the caller will use the release html_url instead).
     */
    private static String pickAssetUrl(JsonObject release, String mcVersion) {
        JsonElement assetsEl = release.get("assets");
        if (assetsEl == null || !assetsEl.isJsonArray()) return null;
        String mcPrefix = mcMajorMinorPatch(mcVersion);
        String fabricMatch = null;
        String anyMatch = null;
        for (JsonElement el : assetsEl.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            JsonObject a = el.getAsJsonObject();
            String name = textOrNull(a, "name");
            String url  = textOrNull(a, "browser_download_url");
            if (name == null || url == null) continue;
            if (!name.endsWith(".jar")) continue;
            anyMatch = url;
            if (mcPrefix != null && name.contains(mcPrefix)) {
                if (name.toLowerCase().contains("fabric")) return url;
                fabricMatch = url;
            }
        }
        return fabricMatch != null ? fabricMatch : anyMatch;
    }

    // ------------------------------------------------------------------------
    // tiny version helpers — semver-ish compare that ignores qualifiers
    // ------------------------------------------------------------------------

    /** {@code 1.21.11-fabric} → {@code 1.21.11}; {@code v1.21} → {@code 1.21}. */
    static String stripV(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        int dash = s.indexOf('-');
        if (dash > 0) s = s.substring(0, dash);
        int plus = s.indexOf('+');
        if (plus > 0) s = s.substring(0, plus);
        return s;
    }

    /** {@code "1.21.11.2"} → {@code "1.21.11"}; null/blank → null. */
    static String mcMajorMinorPatch(String mc) {
        if (mc == null) return null;
        String s = stripV(mc);
        if (s == null || s.isBlank()) return null;
        String[] parts = s.split("\\.");
        if (parts.length < 2) return s;
        if (parts.length == 2) return parts[0] + "." + parts[1];
        return parts[0] + "." + parts[1] + "." + parts[2];
    }

    /** Returns >0 if {@code a > b}, <0 if {@code a < b}, 0 if equal. */
    static int compareVersions(String a, String b) {
        int[] na = numericParts(a);
        int[] nb = numericParts(b);
        int n = Math.max(na.length, nb.length);
        for (int i = 0; i < n; i++) {
            int x = i < na.length ? na[i] : 0;
            int y = i < nb.length ? nb[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int[] numericParts(String s) {
        if (s == null) return new int[0];
        String t = stripV(s);
        if (t == null || t.isBlank()) return new int[0];
        String[] parts = t.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                // tolerate stray non-digits in a segment
                String digits = parts[i].replaceAll("[^0-9].*$", "");
                out[i] = digits.isBlank() ? 0 : Integer.parseInt(digits);
            } catch (NumberFormatException nfe) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static String textOrNull(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        try { return el.getAsString(); } catch (Throwable t) { return null; }
    }

    private static boolean boolOr(JsonObject obj, String key, boolean dflt) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return dflt;
        try { return el.getAsBoolean(); } catch (Throwable t) { return dflt; }
    }
}

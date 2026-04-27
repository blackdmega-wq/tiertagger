package com.outertiers.tiertagger.common;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight "is there a newer TierTagger release?" checker.
 *
 * <p>Queries the GitHub Releases API (no auth required, public repo) once
 * per process at init. Results are cached in {@link #LATEST_VERSION}; the
 * consuming UI code reads {@link #isOutdated()} and {@link #latestVersion()}
 * to decide whether to show the user an "Update available" banner.</p>
 *
 * <p>Failures (DNS, rate limit, parse error, …) are silently swallowed —
 * a missing update banner is a much better failure mode than a crashing
 * client. We never throw out of any public method.</p>
 */
public final class UpdateChecker {
    private UpdateChecker() {}

    /** GitHub Releases JSON for the upstream repo. */
    private static final String RELEASES_URL =
        "https://api.github.com/repos/blackdmega-wq/tiertagger/releases/latest";

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(6))
        .executor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "TierTagger-UpdateCheck");
            t.setDaemon(true);
            return t;
        }))
        .build();

    /** Latest tag name as reported by the upstream releases feed (e.g. "v1.21.11.48"). */
    private static final AtomicReference<String> LATEST_VERSION = new AtomicReference<>(null);
    /** True once we've fetched (success OR failure) so we don't re-fire forever. */
    private static volatile boolean checkStarted = false;

    /** Kick off the async check. Safe to call multiple times — only the first fires. */
    public static synchronized void checkAsync() {
        if (checkStarted) return;
        checkStarted = true;
        CompletableFuture
            .supplyAsync(UpdateChecker::fetchLatestSync, HTTP.executor().orElseThrow())
            .thenAccept(v -> {
                if (v != null && !v.isBlank()) {
                    LATEST_VERSION.set(v);
                    if (isOutdated()) {
                        TierTaggerCore.LOGGER.warn(
                            "[TierTagger] You are running v{} but v{} is available! " +
                            "Get the latest at https://modrinth.com/mod/tiertagger or " +
                            "https://github.com/blackdmega-wq/tiertagger/releases",
                            TierTaggerCore.MOD_VERSION, v);
                    } else {
                        TierTaggerCore.LOGGER.info(
                            "[TierTagger] You are on the latest version (v{})", v);
                    }
                }
            })
            .exceptionally(t -> {
                TierTaggerCore.LOGGER.debug("[TierTagger] update check error", t);
                return null;
            });
    }

    private static String fetchLatestSync() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(RELEASES_URL))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "TierTagger/" + TierTaggerCore.MOD_VERSION + " (+https://github.com/blackdmega-wq/tiertagger)")
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) return null;
            // Cheap, dependency-free JSON tag-name extraction. We only need
            // the "tag_name" field; bringing in a JSON parser for one
            // string would be overkill (and we'd then have to deal with
            // multi-loader classpath scoping).
            Matcher m = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"").matcher(resp.body());
            if (!m.find()) return null;
            return m.group(1).trim();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Returns the latest tag known to this process, or {@code null} if not yet known. */
    public static String latestVersion() { return LATEST_VERSION.get(); }

    /**
     * Returns true if a known-newer release exists on GitHub. False if we
     * haven't fetched yet, the fetch failed, or we're on the latest.
     */
    public static boolean isOutdated() {
        String latest = LATEST_VERSION.get();
        if (latest == null || latest.isBlank()) return false;
        return compareVersions(TierTaggerCore.MOD_VERSION, latest) < 0;
    }

    /**
     * Lex-compares two dot-separated numeric versions. Non-numeric segments
     * sort lexicographically. Returns &lt;0 if {@code a} is older than {@code b},
     * 0 if equal, &gt;0 if newer.
     */
    static int compareVersions(String a, String b) {
        if (a == null || b == null) return 0;
        String[] aa = a.split("[._\\-+]");
        String[] bb = b.split("[._\\-+]");
        int n = Math.max(aa.length, bb.length);
        for (int i = 0; i < n; i++) {
            String ap = i < aa.length ? aa[i] : "0";
            String bp = i < bb.length ? bb[i] : "0";
            int cmp;
            try {
                cmp = Integer.compare(Integer.parseInt(ap), Integer.parseInt(bp));
            } catch (NumberFormatException ex) {
                cmp = ap.compareTo(bp);
            }
            if (cmp != 0) return cmp;
        }
        return 0;
    }
}

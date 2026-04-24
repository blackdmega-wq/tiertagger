package com.outertiers.tiertagger.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-service data for one player. Immutable snapshot — refresh by replacing the value.
 */
public final class ServiceData {
    public final TierService            service;
    public final Map<String, Ranking>   rankings; // mode -> ranking
    public final String                 region;   // "EU", "NA", null if unknown
    public final int                    points;   // service-defined leaderboard points
    public final int                    overall;  // overall rank position
    public final long                   fetchedAt;
    public final boolean                missing;  // true => 404 / not on this service

    public ServiceData(TierService service, Map<String, Ranking> rankings, String region,
                       int points, int overall, long fetchedAt, boolean missing) {
        this.service   = service;
        this.rankings  = rankings == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(rankings));
        this.region    = region;
        this.points    = points;
        this.overall   = overall;
        this.fetchedAt = fetchedAt;
        this.missing   = missing;
    }

    public static ServiceData missing(TierService s) {
        return new ServiceData(s, Map.of(), null, 0, 0, System.currentTimeMillis(), true);
    }

    public static ServiceData loading(TierService s) {
        return new ServiceData(s, Map.of(), null, 0, 0, 0L, false);
    }

    /** Returns the highest-scoring ranking (HT1 wins). */
    public Ranking highest() {
        Ranking best = null;
        for (Ranking r : rankings.values()) {
            if (r == null || r.tierLevel <= 0) continue;
            if (best == null || r.score() > best.score()) best = r;
        }
        return best;
    }

    /** Returns the highest tier label like "HT3" or "" if none. */
    public String highestLabel() {
        Ranking r = highest();
        return r == null ? "" : r.label();
    }

    /** Number of modes the player is actually ranked in. */
    public int rankedCount() {
        int n = 0;
        for (Ranking r : rankings.values()) if (r != null && r.tierLevel > 0) n++;
        return n;
    }
}

package com.outertiers.tiertagger.common;

import java.util.EnumMap;
import java.util.Map;

/**
 * Aggregated tier data for one player across every service we know about.
 * The map always contains an entry for each service, even if the data is
 * still loading or marked missing. This keeps the screens simple — they can
 * iterate {@link TierService#values()} and never have to null-check.
 */
public final class PlayerData {
    public final String username;
    public final String uuidNoDash;     // may be null while resolving / on offline players
    public final EnumMap<TierService, ServiceData> services;
    public final long   fetchedAt;

    public PlayerData(String username, String uuidNoDash) {
        this.username   = username == null ? "" : username;
        this.uuidNoDash = uuidNoDash;
        this.services   = new EnumMap<>(TierService.class);
        for (TierService s : TierService.values()) services.put(s, ServiceData.loading(s));
        this.fetchedAt  = System.currentTimeMillis();
    }

    public ServiceData get(TierService s) {
        ServiceData d = services.get(s);
        return d == null ? ServiceData.loading(s) : d;
    }

    /** Whether ALL services have either succeeded or definitively reported missing. */
    public boolean fullyResolved() {
        for (ServiceData d : services.values()) {
            if (d.fetchedAt == 0L) return false;
        }
        return true;
    }

    /** Whether NO service returned data (every one is missing). */
    public boolean allMissing() {
        if (!fullyResolved()) return false;
        for (ServiceData d : services.values()) {
            if (!d.missing) return false;
        }
        return true;
    }

    /** Returns a stable map snapshot for display. */
    public Map<TierService, ServiceData> snapshot() {
        return new EnumMap<>(services);
    }
}

package com.outertiers.tiertagger.common;

import java.util.EnumMap;
import java.util.Map;

/**
 * Aggregated tier data for one player across every service we know about.
 *
 * NOTE: {@code uuidNoDash} is intentionally NOT final — the cache writes it
 * lazily once the UUID has been resolved so we never need to replace the
 * entire object, which previously caused a race condition between concurrent
 * service-fetch threads writing results to stale object references.
 */
public final class PlayerData {
    public final String username;
    /** 32-hex UUID without dashes. May be null while resolving / on offline servers. Volatile for thread-safe lazy init. */
    public volatile String uuidNoDash;
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

package com.outertiers.tiertagger.common;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The four tier-list services TierTagger pulls from. Each service has its own
 * set of supported gamemodes, its own colour identity, its own API endpoint
 * shape, and its own short identifier used in the tab list / nametag badges.
 *
 * Three of the four (MCTIERS, SUBTIERS, PVPTIERS) share the same upstream JSON
 * shape:
 *   { uuid, name, region, points, overall,
 *     rankings: { <mode>: { tier, pos, peak_tier, peak_pos, retired, attained } } }
 * where {@code pos == 0} means "high tier" and {@code pos == 1} means "low tier".
 *
 * OUTERTIERS uses a separate, username-keyed JSON shape with explicit
 * {@code rawTiers} ("HT3", "LT4", …) and a top-level {@code peakTier}.
 */
public enum TierService {
    // NOTE on mode lists:
    //   - "crystal" is intentionally OMITTED from MCTIERS / PVPTIERS because their
    //     "vanilla" mode IS crystal-pvp (Vanilla == Crystal on those services).
    //   - "sumo" is intentionally OMITTED — it's never been a real gamemode on
    //     mctiers.com / pvptiers.com and showing it produced a permanent
    //     "not on this list" row for every player.
    MCTIERS(
        "mctiers",  "MCTiers",   "MCT",  0xFFE53935,
        "https://mctiers.com/api/profile/",  Lookup.UUID_NODASH,
        List.of("vanilla", "sword", "axe", "pot", "nethpot", "smp", "uhc", "mace")
    ),
    OUTERTIERS(
        "outertiers", "OuterTiers", "OT",  0xFFFFB300,
        "https://outertiers-api.onrender.com/api/players/", Lookup.USERNAME,
        List.of("ogvanilla", "vanilla", "uhc", "pot", "nethop", "smp", "sword", "axe", "mace", "speed")
    ),
    PVPTIERS(
        "pvptiers", "PvPTiers",  "PVP",  0xFF1E88E5,
        "https://pvptiers.com/api/profile/", Lookup.UUID_NODASH,
        List.of("vanilla", "sword", "axe", "pot", "nethpot", "smp", "uhc", "mace")
    ),
    // SubTiers exposes the niche / community-driven gamemodes that mainline
    // tier lists don't track. Order matches subtiers.net's own profile layout.
    SUBTIERS(
        "subtiers", "SubTiers",  "ST",   0xFF8E24AA,
        "https://subtiers.net/api/profile/", Lookup.UUID_NODASH,
        List.of("og_vanilla", "sword", "axe", "mace", "uhc", "speed", "bed", "elytra",
                "trident", "creeper", "minecart", "manhunt", "dia_smp", "dia_crystal",
                "dia_2v2", "debuff", "bow")
    );

    public enum Lookup { USERNAME, UUID_NODASH }

    public final String  id;          // stable lowercase id used in config json
    public final String  displayName; // e.g. "MCTiers"
    public final String  shortLabel;  // 2–3 char abbreviation for badges
    public final int     accentArgb;  // brand accent (used as fallback when no logo texture is loaded)
    public final String  apiBase;     // full URL prefix; append uuid or username
    public final Lookup  lookup;
    public final List<String> modes;  // in display order

    TierService(String id, String displayName, String shortLabel, int accentArgb,
                String apiBase, Lookup lookup, List<String> modes) {
        this.id          = id;
        this.displayName = displayName;
        this.shortLabel  = shortLabel;
        this.accentArgb  = accentArgb;
        this.apiBase     = apiBase;
        this.lookup      = lookup;
        this.modes       = modes;
    }

    public static TierService byId(String id) {
        if (id == null) return null;
        for (TierService s : values()) if (s.id.equalsIgnoreCase(id) || s.name().equalsIgnoreCase(id)) return s;
        return null;
    }

    public static TierService byIdOr(String id, TierService fallback) {
        TierService s = byId(id);
        return s == null ? fallback : s;
    }

    /** Default per-service enabled state. */
    public static Map<TierService, Boolean> defaultEnabled() {
        Map<TierService, Boolean> m = new LinkedHashMap<>();
        for (TierService s : values()) m.put(s, true);
        return m;
    }

    /** Returns the union of every service's modes (deduped, in service order). */
    public static Set<String> allKnownModes() {
        Set<String> set = new LinkedHashSet<>();
        for (TierService s : values()) set.addAll(s.modes);
        return set;
    }

    /** Modes shared by ALL enabled services (intersection). */
    public static Set<String> intersectionOfModes(Iterable<TierService> services) {
        LinkedHashSet<String> result = null;
        for (TierService s : services) {
            if (result == null) result = new LinkedHashSet<>(s.modes);
            else result.retainAll(s.modes);
        }
        return result == null ? new LinkedHashSet<>() : result;
    }

    /** All values as an immutable list, useful for cycling. */
    public static List<TierService> list() { return Arrays.asList(values()); }
}

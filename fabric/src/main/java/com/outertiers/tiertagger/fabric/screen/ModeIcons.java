package com.outertiers.tiertagger.fabric.screen;

import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps tier-list mode ids to the OuterTiers website icon textures bundled in
 * {@code assets/tiertagger/textures/icons/}. Returns {@code null} when no
 * texture is available so callers can fall back to the legacy item icon.
 *
 * Adding a new icon: drop a 64x64 PNG into the resources folder above and
 * register the mode → file mapping in the static block below.
 */
public final class ModeIcons {

    private static final String NS = "tiertagger";
    private static final Map<String, String> FILES = new HashMap<>();

    /**
     * Service-specific icon overrides. Keyed first by TierService.id (e.g.
     * "pvptiers"), then by mode key. When a service has its own art for a
     * given mode (e.g. PvPTiers ships a different sword/uhc/pot/… style than
     * the OuterTiers/MCTiers shared set), the file lives under a per-service
     * subfolder of {@code textures/icons/} (e.g. {@code textures/icons/pvptiers/sword.png})
     * and gets registered here. Falls back to the shared {@link #FILES} map
     * when no override exists for that (service, mode) pair.
     */
    private static final Map<String, Map<String, String>> SERVICE_FILES = new HashMap<>();

    static {
        // canonical id → png file name (without the .png suffix).
        // Aliases (e.g. "og_vanilla" / "ogvanilla", "nethpot" / "nethop") all
        // resolve to the same canonical PNG so we don't ship duplicate art.
        put("vanilla",     "vanilla");
        put("ogvanilla",   "ogvanilla");
        put("og_vanilla",  "og_vanilla");
        put("uhc",         "uhc");
        put("pot",         "pot");
        put("nethpot",     "nethop");
        put("nethop",      "nethop");
        put("neth_pot",    "nethop");
        put("smp",         "smp");
        put("sword",       "sword");
        put("axe",         "axe");
        put("mace",        "mace");
        put("speed",       "speed");
        put("2v2",         "2v2");
        put("dia2v2",      "2v2");
        put("dia_2v2",     "2v2");
        put("overall",     "overall");
        // PvPTiers crystal: official icon from pvptiers.com/icons/tiers/
        // (re-cropped to fill the 64×64 canvas the same way the other
        // bundled icons do, so it doesn't look smaller in the UI).
        put("crystal",     "crystal");
        // SubTiers / OuterTiers gamemodes — original bundled artwork.
        put("trident",     "trident");
        put("creeper",     "creeper");
        put("minecart",    "minecart");
        put("manhunt",     "manhunt");
        put("dia_smp",     "dia_smp");
        put("dia_crystal", "dia_crystal");
        put("debuff",      "debuff");
        put("bow",         "bow");
        put("sumo",        "sumo");
        put("bed",         "bed");
        put("bedwars",     "bed");
        put("elytra",      "elytra");

        // ── PvPTiers per-service overrides ──────────────────────────────────
        // The shared icon set above mirrors mctiers.com / OuterTiers art,
        // which does NOT match what pvptiers.com displays. The user wants
        // PvPTiers rows to use the official pvptiers.com art. These eight
        // PNGs were pulled from https://pvptiers.com/icons/tiers/<mode>.png
        // and resampled to 64×64 to match the rest of the icon set, then
        // dropped under textures/icons/pvptiers/. Note pvptiers.com names
        // the netherite-pot icon "neth_pot.png" while our internal mode
        // key is "nethpot", so the file is renamed on disk to nethpot.png.
        putService("pvptiers", "crystal", "pvptiers/crystal");
        putService("pvptiers", "sword",   "pvptiers/sword");
        putService("pvptiers", "uhc",     "pvptiers/uhc");
        putService("pvptiers", "pot",     "pvptiers/pot");
        putService("pvptiers", "nethpot", "pvptiers/nethpot");
        putService("pvptiers", "neth_pot","pvptiers/nethpot");
        putService("pvptiers", "nethop",  "pvptiers/nethpot");
        putService("pvptiers", "smp",     "pvptiers/smp");
        putService("pvptiers", "axe",     "pvptiers/axe");
        putService("pvptiers", "mace",    "pvptiers/mace");

        // ── OuterTiers per-service overrides (v1.21.11.56) ──────────────────
        // Use the official outertiers.com gamemode art (lifted from
        // OuterTiers/public/tier_icons/, re-encoded to 8-bit RGBA, 64×64).
        // OuterTiers' "Vanilla" mode is Crystal-PvP — the website renamed
        // it to "Crystal" — so vanilla.png here is the crystal artwork.
        putService("outertiers", "ogvanilla", "outertiers/ogvanilla");
        putService("outertiers", "vanilla",   "outertiers/vanilla");
        putService("outertiers", "uhc",       "outertiers/uhc");
        putService("outertiers", "pot",       "outertiers/pot");
        putService("outertiers", "nethop",    "outertiers/nethop");
        putService("outertiers", "smp",       "outertiers/smp");
        putService("outertiers", "sword",     "outertiers/sword");
        putService("outertiers", "axe",       "outertiers/axe");
        putService("outertiers", "mace",      "outertiers/mace");
        putService("outertiers", "speed",     "outertiers/speed");
    }

    private ModeIcons() {}

    private static void put(String k, String file) {
        FILES.put(k.toLowerCase(Locale.ROOT), file);
    }

    private static void putService(String svc, String mode, String file) {
        SERVICE_FILES
            .computeIfAbsent(svc.toLowerCase(Locale.ROOT), k -> new HashMap<>())
            .put(mode.toLowerCase(Locale.ROOT), file);
    }

    /** Identifier of the bundled PNG for {@code mode}, or {@code null}. */
    public static Identifier textureFor(String mode) {
        return textureFor(null, mode);
    }

    /**
     * Service-aware variant: prefers a per-service icon override (e.g. the
     * pvptiers/ subfolder) when one exists for {@code (serviceId, mode)},
     * otherwise falls back to the shared icon set used by every service.
     * Returns {@code null} when no bundled PNG matches — callers then fall
     * back to the legacy item-icon path.
     */
    public static Identifier textureFor(String serviceId, String mode) {
        if (mode == null) return null;
        String key = mode.toLowerCase(Locale.ROOT);
        String f = null;
        if (serviceId != null) {
            Map<String, String> svcMap = SERVICE_FILES.get(serviceId.toLowerCase(Locale.ROOT));
            if (svcMap != null) f = svcMap.get(key);
        }
        if (f == null) f = FILES.get(key);
        if (f == null) return null;
        try {
            return Identifier.of(NS, "textures/icons/" + f + ".png");
        } catch (Throwable t) {
            return null;
        }
    }
}

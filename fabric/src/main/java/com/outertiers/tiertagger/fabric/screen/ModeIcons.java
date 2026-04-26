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
        // SubTiers-specific gamemodes
        put("trident",     "trident");
        put("creeper",     "creeper");
        put("minecart",    "minecart");
        put("manhunt",     "manhunt");
        put("dia_smp",     "dia_smp");
        put("dia_crystal", "dia_crystal");
        put("debuff",      "debuff");
        put("bow",         "bow");
        put("sumo",        "sumo");
        // NOTE: PvPTiers "crystal", SubTiers "elytra", and SubTiers
        // "bedwars"/"bed" are intentionally NOT registered here. Their
        // bundled PNGs (crystal.png, elytra.png, bed.png) used the wrong
        // visuals (an amethyst-style gem, a teal triangle, and a vague
        // red bar) which made them unrecognisable at icon size. By
        // omitting them here, the screen renderer falls through to
        // ctx.drawItem() with the proper Minecraft items configured in
        // {@link com.outertiers.tiertagger.common.TierIcons} (END_CRYSTAL,
        // ELYTRA, RED_BED) — those are the canonical, instantly
        // recognisable game icons the user asked for.
    }

    private ModeIcons() {}

    private static void put(String k, String file) {
        FILES.put(k.toLowerCase(Locale.ROOT), file);
    }

    /** Identifier of the bundled PNG for {@code mode}, or {@code null}. */
    public static Identifier textureFor(String mode) {
        if (mode == null) return null;
        String f = FILES.get(mode.toLowerCase(Locale.ROOT));
        if (f == null) return null;
        try {
            return Identifier.of(NS, "textures/icons/" + f + ".png");
        } catch (Throwable t) {
            return null;
        }
    }
}

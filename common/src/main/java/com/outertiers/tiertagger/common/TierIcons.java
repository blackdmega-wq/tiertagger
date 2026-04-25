package com.outertiers.tiertagger.common;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps tier-list mode ids to a vanilla Minecraft item id used as the mode's
 * icon in the profile / config screens. The platform layer translates the
 * returned id into the appropriate {@code ItemStack} ({@code Items.X} on Fabric,
 * {@code BuiltInRegistries.ITEM} on NeoForge).
 *
 * Every key is normalised to lowercase. A few common synonyms ("og_vanilla"
 * vs "ogvanilla", "nethpot" vs "nethop") are folded into one canonical icon.
 */
public final class TierIcons {

    /** Default fallback when a mode has no specific icon mapping. */
    public static final String DEFAULT_ICON = "minecraft:diamond_sword";

    private static final Map<String, String> ICONS = new LinkedHashMap<>();
    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        // mode id (canonical) -> minecraft item id, human label
        put("vanilla",     "minecraft:netherite_sword",   "Vanilla");
        put("ogvanilla",   "minecraft:iron_sword",        "OG Vanilla");
        put("og_vanilla",  "minecraft:iron_sword",        "OG Vanilla");
        put("uhc",         "minecraft:golden_apple",      "UHC");
        put("pot",         "minecraft:splash_potion",     "Pot");
        put("nethpot",     "minecraft:lingering_potion",  "NethPot");
        put("nethop",      "minecraft:lingering_potion",  "NethOp");
        put("neth_pot",    "minecraft:lingering_potion",  "NethPot");
        put("smp",         "minecraft:diamond_chestplate","SMP");
        put("sword",       "minecraft:diamond_sword",     "Sword");
        put("axe",         "minecraft:diamond_axe",       "Axe");
        put("mace",        "minecraft:mace",              "Mace");
        put("speed",       "minecraft:feather",           "Speed");
        put("bed",         "minecraft:red_bed",           "Bedwars");
        put("elytra",      "minecraft:elytra",            "Elytra");
        put("crystal",     "minecraft:end_crystal",       "Crystal");
        put("sumo",        "minecraft:slime_block",       "Sumo");
        // SubTiers-specific gamemodes
        put("trident",     "minecraft:trident",           "Trident");
        put("creeper",     "minecraft:creeper_head",      "Creeper");
        put("minecart",    "minecraft:minecart",          "Minecart");
        put("manhunt",     "minecraft:compass",           "Manhunt");
        put("dia_smp",     "minecraft:diamond",           "Dia SMP");
        put("dia_crystal", "minecraft:end_crystal",       "Dia Crystal");
        put("dia_2v2",     "minecraft:diamond_axe",       "Dia 2v2");
        put("debuff",      "minecraft:potion",            "Debuff");
        put("bow",         "minecraft:bow",               "Bow");
        put("2v2",         "minecraft:netherite_axe",     "2v2");
        put("overall",     "minecraft:nether_star",       "Overall");
    }

    private static void put(String k, String item, String label) {
        ICONS.put(k.toLowerCase(Locale.ROOT), item);
        LABELS.put(k.toLowerCase(Locale.ROOT), label);
    }

    private TierIcons() {}

    /** Returns the minecraft item id for a mode (or the default). */
    public static String iconFor(String mode) {
        if (mode == null) return DEFAULT_ICON;
        String v = ICONS.get(mode.toLowerCase(Locale.ROOT));
        return v == null ? DEFAULT_ICON : v;
    }

    /** Returns a human-friendly label for a mode (e.g. "vanilla" → "Vanilla"). */
    public static String labelFor(String mode) {
        if (mode == null) return "";
        String v = LABELS.get(mode.toLowerCase(Locale.ROOT));
        if (v != null) return v;
        // capitalise first letter as a fallback
        if (mode.isEmpty()) return mode;
        return Character.toUpperCase(mode.charAt(0)) + mode.substring(1);
    }
}

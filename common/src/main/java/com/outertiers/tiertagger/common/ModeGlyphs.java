package com.outertiers.tiertagger.common;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps a tier-list gamemode id to a Private-Use-Area codepoint registered in
 * {@code assets/tiertagger/font/icons.json}. The platform-side renderer
 * stamps the glyph with that font so the icon shows up inline next to the
 * player name in the tab list and above the nametag, exactly the same way
 * vanilla MC inlines its own icons (heart, hunger, etc.).
 *
 * Returns {@code null} when no glyph exists for the requested mode, so the
 * caller can decide whether to omit the icon or fall back to a text label.
 */
public final class ModeGlyphs {
    private ModeGlyphs() {}

    /** Identifier (namespace + path) of the bundled font definition. */
    public static final String FONT_NAMESPACE = "tiertagger";
    public static final String FONT_PATH      = "icons";

    /**
     * Identifier path of the OuterTiers-specific bitmap font (v1.21.11.57).
     * Same Private-Use-Area codepoints as the shared {@link #FONT_PATH} font,
     * but the underlying bitmap glyphs point at the official outertiers.com
     * gamemode artwork (textures/icons/outertiers/*.png) so OT badges in the
     * tab list and above nametags use the per-service art the user requested.
     */
    public static final String FONT_PATH_OUTERTIERS = "icons_outertiers";

    private static final Map<String, String> GLYPHS = new HashMap<>();

    static {
        // Keep these codepoints in lock-step with assets/tiertagger/font/icons.json.
        // Anything in U+E000..U+F8FF is in the Unicode Private Use Area and will
        // never collide with a real character.
        put("vanilla",     "\uE001");
        put("sword",       "\uE002");
        put("axe",         "\uE003");
        put("pot",         "\uE004");
        put("nethop",      "\uE005");
        put("nethpot",     "\uE005");
        put("neth_pot",    "\uE005");
        put("smp",         "\uE006");
        put("uhc",         "\uE007");
        put("mace",        "\uE008");
        put("speed",       "\uE009");
        put("og_vanilla",  "\uE00A");
        put("ogvanilla",   "\uE00B");
        put("bed",         "\uE00C");
        put("bedwars",     "\uE00C");
        put("elytra",      "\uE00D");
        put("2v2",         "\uE00E");
        put("dia_2v2",     "\uE00E");
        put("dia2v2",      "\uE00E");
        put("overall",     "\uE00F");
        put("trident",     "\uE010");
        put("creeper",     "\uE011");
        put("minecart",    "\uE012");
        put("manhunt",     "\uE013");
        put("dia_smp",     "\uE014");
        put("dia_crystal", "\uE015");
        put("debuff",      "\uE016");
        put("bow",         "\uE017");
        put("crystal",     "\uE018");
        put("sumo",        "\uE019");
    }

    private static void put(String mode, String glyph) {
        GLYPHS.put(mode.toLowerCase(Locale.ROOT), glyph);
    }

    /** Returns the single-char glyph string for a mode, or {@code null}. */
    public static String glyphFor(String mode) {
        if (mode == null) return null;
        return GLYPHS.get(mode.toLowerCase(Locale.ROOT));
    }
}

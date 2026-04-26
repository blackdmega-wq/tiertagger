package com.outertiers.tiertagger.fabric.compat;

import com.mojang.authlib.GameProfile;

import java.util.UUID;

/**
 * Reflective accessors for {@link GameProfile} that work with both the
 * legacy bean-style accessors ({@code getName()} / {@code getId()}) used
 * by Mojang authlib 4.x bundled with MC 1.20-1.21.4, and the new record
 * accessors ({@code name()} / {@code id()}) used by authlib 6.x bundled
 * with MC 1.21.5+. Returns {@code null} on any failure.
 */
public final class Profiles {
    private Profiles() {}

    public static String name(GameProfile p) {
        if (p == null) return null;
        try { return (String) GameProfile.class.getMethod("name").invoke(p); }
        catch (Throwable ignored) {}
        try { return (String) GameProfile.class.getMethod("getName").invoke(p); }
        catch (Throwable ignored) {}
        return null;
    }

    public static UUID id(GameProfile p) {
        if (p == null) return null;
        try {
            Object v = GameProfile.class.getMethod("id").invoke(p);
            if (v instanceof UUID u) return u;
        } catch (Throwable ignored) {}
        try {
            Object v = GameProfile.class.getMethod("getId").invoke(p);
            if (v instanceof UUID u) return u;
        } catch (Throwable ignored) {}
        return null;
    }

    /** Mojang's API-friendly UUID rendering (32 hex chars, no dashes). */
    public static String idHex(GameProfile p) {
        UUID u = id(p);
        return u == null ? null : u.toString().replace("-", "");
    }
}

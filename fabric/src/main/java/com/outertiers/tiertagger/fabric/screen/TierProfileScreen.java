package com.outertiers.tiertagger.fabric.screen;

import net.minecraft.client.gui.screen.Screen;

/**
 * Profile screen alias — the original 2×2 grid layout was unreadable at
 * normal GUI scales (panels too small, header band too dark). The new lookup
 * card layout is strictly better for the same data, so {@code Profile} now
 * extends {@code Lookup} and inherits the same redesign.
 *
 * Both {@code /tiertagger profile} and {@code /tiertagger lookup} therefore
 * open the identical, polished GUI without any behavioural change for callers.
 */
public class TierProfileScreen extends TierLookupScreen {
    public TierProfileScreen(Screen parent, String username) {
        super(parent, username);
    }
}

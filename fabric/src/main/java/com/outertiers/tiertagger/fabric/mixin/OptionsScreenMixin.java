package com.outertiers.tiertagger.fabric.mixin;

import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.fabric.screen.TierConfigScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Injects a "TierTagger" button into Minecraft's Options screen so the user
 * can open the mod settings directly from Options without needing Mod Menu.
 *
 * Placement strategy (v1.21.11.48 — F11/resize-safe rewrite, matches the
 * fabric-modern module):
 *   We place the button RELATIVE to the Credits & Attribution button without
 *   ever mutating the position of any vanilla widget. Earlier versions
 *   shifted the Done button down by one row to make space, which interacted
 *   badly with the new HeaderAndFooterLayout's footer-anchoring on window
 *   resize: minimising the window then pressing F11 to fullscreen would
 *   re-init the screen at the new size, the layout would re-anchor Done,
 *   and our shifted reference would no longer match — visually the
 *   TierTagger button looked like it had "teleported" to a random spot on
 *   the new layout.
 *
 *   The new logic:
 *     1. Find the Credits & Attribution button by its localised text.
 *     2. Find the Done button (also by text) so we know where the footer
 *        starts.
 *     3. Pick a Y for the new button: ideally one row directly under
 *        Credits, but if that would clash with Done (small / tiny window)
 *        clamp it to just above Done so it never overlaps and never gets
 *        clipped off-screen.
 *   No vanilla widget is moved, so subsequent resizes (F11, window drag,
 *   monitor swap) always produce the same correct placement.
 *
 * NOTE: This class extends Screen at compile time so that the `protected`
 * method addDrawableChild is reachable. At runtime Mixin merges the
 * inject method into OptionsScreen itself, so the `extends Screen`
 * declaration is purely a compile-time accessibility shim and is never
 * actually used as a base class.
 */
@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin extends Screen {

    protected OptionsScreenMixin(Text title) {
        super(title);
    }

    /**
     * require = 0: if {@code init()} is ever renamed/refactored on a future MC
     * version, the mixin silently no-ops (user just won't see the in-options
     * shortcut button) instead of preventing the entire mod — and therefore
     * the entire client — from loading.
     */
    @Inject(method = "init", at = @At("TAIL"), require = 0)
    private void tiertagger$addButton(CallbackInfo ci) {
        try {
            ButtonWidget credits = findButtonByKey("credits_and_attribution", "credits");

            if (credits != null) {
                int cx = credits.getX();
                int cy = credits.getY();
                int cw = credits.getWidth();
                int ch = credits.getHeight();
                int rowH = ch + 4;

                // Look up the Done button so we know where the footer starts
                // and can avoid overlapping it. If we can't find it (modded
                // / future MC), fall back to "just don't go off-screen".
                ButtonWidget done = findButtonByKey("done", "fertig", "ok", "schliessen");
                int safeMaxY;
                if (done != null && done != credits) {
                    safeMaxY = done.getY() - rowH;
                } else {
                    safeMaxY = this.height - ch - 4;
                }

                // Ideal Y: one row directly under Credits.
                int idealY = cy + rowH;
                // Clamp so we never overlap Done / leave the screen.
                int targetY = Math.min(idealY, safeMaxY);

                // If even the clamped Y would put us above (or on top of)
                // Credits — i.e. the window is so small there's literally
                // no room — bail to the bottom-left fallback below so we
                // don't draw on top of the Credits button itself.
                if (targetY <= cy) {
                    throw new IllegalStateException("no vertical room for TierTagger button under Credits");
                }

                this.addDrawableChild(
                    ButtonWidget.builder(
                        Text.literal("\u00a7e[TierTagger]"),
                        btn -> {
                            MinecraftClient mc = MinecraftClient.getInstance();
                            if (mc != null) mc.setScreen(new TierConfigScreen((Screen)(Object)this));
                        })
                    .dimensions(cx, targetY, cw, ch)
                    .build()
                );
                return;
            }
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] could not insert Options-screen button under Credits, falling back: {}", t.toString());
        }

        // Fallback: original bottom-left position so older or non-vanilla
        // Options screens still get the shortcut.
        try {
            this.addDrawableChild(
                ButtonWidget.builder(
                    Text.literal("\u00a7e[TierTagger]"),
                    btn -> {
                        MinecraftClient mc = MinecraftClient.getInstance();
                        if (mc != null) mc.setScreen(new TierConfigScreen((Screen)(Object)this));
                    })
                .dimensions(4, this.height - 24, 100, 20)
                .build()
            );
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] could not add Options-screen button: {}", t.toString());
        }
    }

    /**
     * Locate a vanilla {@link ButtonWidget} on the Options screen by matching
     * its visible text against any of the supplied substrings (case-insensitive).
     * Returns {@code null} if no candidate matches — safer than blowing up the
     * Options screen on locales / mods we didn't anticipate.
     *
     * <p>v1.21.11.50: now recurses into nested
     * {@link net.minecraft.client.gui.ParentElement} containers AND scans
     * the screen's private {@code drawables} list via reflection. The
     * previous version walked only {@code this.children()} which, on some
     * post-resize paths (e.g. minimise the window then press F11), missed
     * the Credits / Done widgets — that's why the [TierTagger] button
     * "teleported" to the bottom-left fallback after a fullscreen toggle.
     */
    private ButtonWidget findButtonByKey(String... needles) {
        if (needles == null || needles.length == 0) return null;
        List<String> needleList = new ArrayList<>();
        for (String n : needles) if (n != null && !n.isBlank()) needleList.add(n.toLowerCase(Locale.ROOT));

        java.util.List<ButtonWidget> all = new ArrayList<>();
        java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();

        // (1) Recurse through this.children(); descend into ParentElements.
        try { collectButtons(this.children(), all, seen, 0); } catch (Throwable ignored) {}

        // (2) Scan the screen's private drawable / renderable lists via
        //     reflection. Layout widgets added via addDrawable are present
        //     here even when they're missing from children() during a resize.
        try {
            for (Class<?> c = this.getClass(); c != null; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (!java.util.List.class.isAssignableFrom(f.getType())) continue;
                    String fn = f.getName().toLowerCase(Locale.ROOT);
                    if (!(fn.contains("drawable") || fn.contains("renderable"))) continue;
                    f.setAccessible(true);
                    Object val = f.get(this);
                    if (val instanceof java.util.List<?> list) {
                        for (Object o : list) {
                            if (o instanceof ButtonWidget btn && !seen.containsKey(btn)) {
                                seen.put(btn, Boolean.TRUE);
                                all.add(btn);
                            } else if (o instanceof net.minecraft.client.gui.ParentElement pe) {
                                try { collectButtons(pe.children(), all, seen, 0); } catch (Throwable ignored) {}
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        for (ButtonWidget btn : all) {
            String label;
            try { label = btn.getMessage().getString(); }
            catch (Throwable t) { continue; }
            if (label == null || label.isBlank()) continue;
            String lower = label.toLowerCase(Locale.ROOT);
            for (String needle : needleList) {
                if (lower.contains(needle)) return btn;
            }
        }
        return null;
    }

    private static void collectButtons(java.util.List<? extends Element> in,
                                       java.util.List<ButtonWidget> out,
                                       java.util.IdentityHashMap<Object, Boolean> seen,
                                       int depth) {
        if (in == null || depth > 6) return;
        for (Element el : in) {
            if (el == null || seen.containsKey(el)) continue;
            seen.put(el, Boolean.TRUE);
            if (el instanceof ButtonWidget btn) out.add(btn);
            if (el instanceof net.minecraft.client.gui.ParentElement pe) {
                try { collectButtons(pe.children(), out, seen, depth + 1); } catch (Throwable ignored) {}
            }
        }
    }
}

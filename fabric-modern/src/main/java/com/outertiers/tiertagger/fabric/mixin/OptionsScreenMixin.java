package com.outertiers.tiertagger.fabric.mixin;

import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.fabric.screen.TierConfigScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
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
 * Placement strategy (v1.21.11.52 — self-anchoring rewrite):
 *   The button is a custom {@link Button} subclass that re-computes its
 *   own position on every render frame, RELATIVE to the Credits & Attribution
 *   button it found in the screen. This means:
 *
 *     • F11 (toggle fullscreen) → screen resize → vanilla re-runs init() →
 *       Credits ends up in a new spot → our button reads Credits' NEW
 *       position the very next frame and re-anchors itself.
 *     • Window minimise/restore → same loop, no teleport.
 *     • Multi-monitor swap, DPI change, resolution change → same.
 *     • If Credits ever disappears (modded screen / future MC), the
 *       button falls back to the bottom-left position that all earlier
 *       versions used — never crashes the screen.
 *
 *   Earlier versions read Credits' position ONCE at @Inject(init, TAIL)
 *   and froze the dimensions. On certain HeaderAndFooterLayout post-resize
 *   paths the layout's children weren't fully positioned yet at TAIL, so
 *   the button sometimes anchored to a stale or off-screen Credits and
 *   "teleported" to the wrong spot — exactly the bug the user reported.
 *
 * NOTE: This class extends Screen at compile time so that the `protected`
 * method addDrawableChild is reachable. At runtime Mixin merges the
 * inject method into OptionsScreen itself, so the `extends Screen`
 * declaration is purely a compile-time accessibility shim and is never
 * actually used as a base class.
 */
@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin extends Screen {

    protected OptionsScreenMixin(Component title) {
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
            // Self-anchoring button: re-positions itself relative to Credits
            // on every render frame, so F11 / resize / DPI changes can never
            // leave it in a stale spot.
            Screen self = (Screen)(Object)this;
            this.addDrawableChild(new TierTaggerOptionsButton(self));
            return;
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn(
                "[TierTagger] could not insert self-anchoring Options-screen button: {}",
                t.toString());
        }

        // Fallback: original bottom-left position so older or non-vanilla
        // Options screens still get the shortcut.
        try {
            this.addDrawableChild(
                Button.builder(
                    Component.literal("\u00a7e[TierTagger]"),
                    btn -> {
                        Minecraft mc = Minecraft.getInstance();
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
     * Custom {@link Button} that locates the Credits & Attribution widget
     * on the parent {@link OptionsScreen} every render frame and re-anchors
     * itself one row below it. If Credits can't be found this frame the
     * button falls back to the bottom-left corner so it never disappears.
     *
     * <p>Doing the lookup per-frame (instead of once at init) is what makes
     * the placement F11/minimise-safe — the layout is always read at the
     * SAME moment vanilla draws Credits, so the two stay in sync no matter
     * how many resizes happen between init() and the user actually seeing
     * the screen.
     */
    public static final class TierTaggerOptionsButton extends Button {
        private final Screen parentScreen;

        public TierTaggerOptionsButton(Screen parent) {
            // Initial dimensions are placeholders — render() re-anchors
            // before the first draw, so the user never sees the (4,4) spot.
            super(4, 4, 150, 20,
                  Component.literal("\u00a7e[TierTagger]"),
                  btn -> {
                      Minecraft mc = Minecraft.getInstance();
                      if (mc != null) mc.setScreen(new TierConfigScreen(parent));
                  },
                  DEFAULT_NARRATION);
            this.parentScreen = parent;
            // Compute an initial anchor immediately so the very first
            // render frame has the right hit-rect for input handling.
            anchorToCredits();
        }

        @Override
        protected void renderWidget(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
            // Re-anchor BEFORE drawing so the user never sees a flicker
            // at the wrong position after a screen resize.
            try { anchorToCredits(); } catch (Throwable ignored) {}
            super.renderWidget(ctx, mouseX, mouseY, delta);
        }

        private void anchorToCredits() {
            Screen scr = parentScreen;
            if (scr == null) return;

            Button credits = findButton(scr, "credits_and_attribution", "credits");
            Button done    = findButton(scr, "done", "fertig", "ok", "schliessen");

            if (credits == null) {
                // Fallback: hug the bottom-left so we never vanish entirely.
                this.setX(4);
                this.setY(scr.height - 24);
                this.setWidth(100);
                return;
            }

            int cx = credits.getX();
            int cy = credits.getY();
            int cw = credits.getWidth();
            int ch = credits.getHeight();
            int rowH = ch + 4;

            int safeMaxY = (done != null && done != credits)
                ? done.getY() - rowH
                : scr.height - ch - 4;

            int idealY = cy + rowH;
            int targetY = Math.min(idealY, safeMaxY);

            // If the window is so small there is genuinely no room
            // under Credits, slot in just above Credits instead so we
            // never overlap or vanish off-screen.
            if (targetY <= cy) {
                targetY = Math.max(2, cy - rowH);
            }

            this.setX(cx);
            this.setY(targetY);
            this.setWidth(cw);
        }

        // ── Helpers (duplicated from the outer class so the Button can
        //    locate vanilla widgets without holding a reference to the
        //    mixin shim type) ────────────────────────────────────────────
        private static Button findButton(Screen scr, String... needles) {
            if (needles == null || needles.length == 0) return null;
            List<String> needleList = new ArrayList<>();
            for (String n : needles) if (n != null && !n.isBlank()) needleList.add(n.toLowerCase(Locale.ROOT));

            List<Button> all = new ArrayList<>();
            java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
            try { collect(scr.children(), all, seen, 0); } catch (Throwable ignored) {}

            // Reflective scan of the screen's `renderables` list so we also
            // pick up addRenderableOnly widgets that aren't in children().
            try {
                for (Class<?> c = scr.getClass(); c != null; c = c.getSuperclass()) {
                    for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                        if (!java.util.List.class.isAssignableFrom(f.getType())) continue;
                        if (!f.getName().toLowerCase(Locale.ROOT).contains("renderable")) continue;
                        f.setAccessible(true);
                        Object val = f.get(scr);
                        if (val instanceof java.util.List<?> list) {
                            for (Object o : list) {
                                if (o instanceof Button btn && !seen.containsKey(btn)) {
                                    seen.put(btn, Boolean.TRUE);
                                    all.add(btn);
                                } else if (o instanceof net.minecraft.client.gui.components.events.ContainerEventHandler ceh) {
                                    try { collect(ceh.children(), all, seen, 0); } catch (Throwable ignored) {}
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}

            for (Button btn : all) {
                String label;
                try { label = btn.getMessage().getString(); } catch (Throwable t) { continue; }
                if (label == null || label.isBlank()) continue;
                // Skip our own button so we never anchor to ourselves.
                if (btn instanceof TierTaggerOptionsButton) continue;
                String lower = label.toLowerCase(Locale.ROOT);
                for (String needle : needleList) {
                    if (lower.contains(needle)) return btn;
                }
            }
            return null;
        }

        private static void collect(List<? extends GuiEventListener> in,
                                    List<Button> out,
                                    java.util.IdentityHashMap<Object, Boolean> seen,
                                    int depth) {
            if (in == null || depth > 6) return;
            for (GuiEventListener el : in) {
                if (el == null || seen.containsKey(el)) continue;
                seen.put(el, Boolean.TRUE);
                if (el instanceof Button btn) out.add(btn);
                if (el instanceof net.minecraft.client.gui.components.events.ContainerEventHandler ceh) {
                    try { collect(ceh.children(), out, seen, depth + 1); } catch (Throwable ignored) {}
                }
            }
        }
    }
}

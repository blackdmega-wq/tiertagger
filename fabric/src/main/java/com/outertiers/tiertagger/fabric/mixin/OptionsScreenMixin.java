package com.outertiers.tiertagger.fabric.mixin;

import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.fabric.compat.Compat;
import com.outertiers.tiertagger.fabric.screen.TierConfigScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Injects a small square OuterTiers icon button into Minecraft's Options
 * screen so the user can open the mod settings directly from Options
 * without needing Mod Menu.
 *
 * Placement strategy (v1.21.11.52 — icon-next-to-Controls rewrite):
 *   The button is a 20×20 self-anchoring icon button that re-computes its
 *   position on every render frame, RELATIVE to the vanilla "Controls"
 *   button it locates by text. This means F11 (toggle fullscreen), window
 *   resize, DPI / monitor swap etc. can never leave it in a stale spot —
 *   the very next frame re-anchors it to wherever vanilla now draws
 *   Controls. Falls back to the bottom-right corner if Controls can't be
 *   found (modded / future MC) so the shortcut never disappears.
 *
 *   v1.21.11.51 and earlier placed a wide [TierTagger] button under
 *   Credits & Attribution. That placement has been removed in this
 *   release per user request — the icon-next-to-Controls placement is
 *   tighter, looks native, and matches the OuterTiers branding.
 *
 * NOTE: This class extends Screen at compile time so that the {@code protected}
 * methods {@code addDrawableChild} and {@code addRenderableWidget} are
 * reachable. At runtime Mixin merges the inject method into OptionsScreen
 * itself, so the {@code extends Screen} declaration is purely a compile-time
 * accessibility shim and is never actually used as a base class.
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
            Screen self = (Screen)(Object)this;
            this.addDrawableChild(new TierTaggerIconButton(self));
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn(
                "[TierTagger] could not insert Options-screen icon button: {}",
                t.toString());
        }
    }

    /**
     * Self-anchoring 20×20 icon button. Locates the vanilla "Controls"
     * button on the parent {@link OptionsScreen} every render frame and
     * re-positions itself one button-width to the right of it. If
     * Controls can't be found this frame the button falls back to the
     * bottom-right corner so it never disappears.
     *
     * <p>Doing the lookup per-frame (instead of once at init) is what
     * makes the placement F11/minimise-safe — the layout is always read
     * at the SAME moment vanilla draws Controls, so the two stay in
     * sync no matter how many resizes happen between init() and the
     * user actually seeing the screen.
     */
    public static final class TierTaggerIconButton extends ButtonWidget {
        private static final Identifier OT_LOGO =
            Identifier.of("tiertagger", "textures/logo/outertiers.png");
        private static final int SIZE = 20;
        private static final int GAP  = 4;

        private final Screen parentScreen;

        public TierTaggerIconButton(Screen parent) {
            // Initial dimensions are placeholders — render() re-anchors
            // before the first draw, so the user never sees the (4,4) spot.
            super(4, 4, SIZE, SIZE,
                  net.minecraft.text.Text.literal(""),
                  btn -> {
                      MinecraftClient mc = MinecraftClient.getInstance();
                      if (mc != null) mc.setScreen(new TierConfigScreen(parent));
                  },
                  DEFAULT_NARRATION_SUPPLIER);
            this.parentScreen = parent;
            // Tooltip so users know what the icon does on first hover.
            try {
                this.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                    net.minecraft.text.Text.literal("\u00a7eTierTagger\u00a7r\nOpen the OuterTiers configuration screen.")));
            } catch (Throwable ignored) {}
            // Compute an initial anchor immediately so the very first
            // render frame has the right hit-rect for input handling.
            try { anchorToControls(); } catch (Throwable ignored) {}
        }

        /**
         * PressableWidget's only abstract method on 1.21.5+. Called from
         * the (final) renderWidget after the button frame is painted.
         *
         * <p>We re-anchor to Controls at the top of every frame so window
         * resize / F11 / DPI changes track. {@code render()} and
         * {@code renderWidget()} on ClickableWidget / PressableWidget are
         * both final in 1.21.5+, so {@code drawIcon} is the only per-
         * frame hook we have. The button frame for THIS frame may be one
         * frame stale after a resize, but vanilla also re-runs
         * {@link Screen#init()} on resize which re-anchors via the
         * constructor — so the visible flicker is at most a single frame.
         *
         * <p>After anchoring we paint the OT logo inset 2 px on every
         * side, with a yellow "OT" monogram fallback if the bundled
         * texture is missing.
         */
        @Override
        protected void drawIcon(DrawContext ctx, int mouseX, int mouseY, float delta) {
            try { anchorToControls(); } catch (Throwable ignored) {}
            int x = this.getX(), y = this.getY(), w = this.getWidth(), h = this.getHeight();
            int icoPad = 2;
            int icoX = x + icoPad;
            int icoY = y + icoPad;
            int icoS = w - icoPad * 2;
            boolean drewLogo = false;
            try {
                Compat.drawTexture(ctx, OT_LOGO, icoX, icoY, 0, 0, icoS, icoS, icoS, icoS);
                drewLogo = true;
            } catch (Throwable ignored) {
                drewLogo = false;
            }
            if (!drewLogo) {
                net.minecraft.text.Text fallback = net.minecraft.text.Text.literal("\u00a7eOT");
                int tw = MinecraftClient.getInstance().textRenderer.getWidth(fallback);
                ctx.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, fallback,
                        x + (w - tw) / 2,
                        y + (h - 8) / 2,
                        0xFFFFFFFF);
            }
        }

        private void anchorToControls() {
            Screen scr = parentScreen;
            if (scr == null) return;

            ButtonWidget controls = findButton(scr,
                "controls",          // English (covers "Controls...")
                "steuerung",         // German
                "commandes",         // French
                "comandos",          // Spanish / Portuguese
                "controlli",         // Italian
                "\u64cd\u4f5c");     // Chinese (操作)

            if (controls == null) {
                // Fallback: hug the bottom-right so we never vanish entirely.
                this.setX(scr.width - SIZE - 4);
                this.setY(scr.height - SIZE - 4);
                this.setWidth(SIZE);
                this.setHeight(SIZE);
                return;
            }

            // Slot one button-gap to the RIGHT of Controls, vertically
            // centred on the same row. Square — width == height == SIZE
            // regardless of how wide Controls is.
            int targetX = controls.getX() + controls.getWidth() + GAP;
            int targetY = controls.getY() + (controls.getHeight() - SIZE) / 2;

            // If the row is so cramped that we'd run off the right edge,
            // slot to the LEFT of Controls instead. Tiny windows still
            // get a usable button, never an off-screen one.
            if (targetX + SIZE > scr.width - 2) {
                targetX = controls.getX() - SIZE - GAP;
                if (targetX < 2) targetX = scr.width - SIZE - 4;
            }

            this.setX(targetX);
            this.setY(targetY);
            this.setWidth(SIZE);
            this.setHeight(SIZE);
        }

        // ── Helpers (duplicated from the outer class so the Button can
        //    locate vanilla widgets without holding a reference to the
        //    mixin shim type) ────────────────────────────────────────────
        private static ButtonWidget findButton(Screen scr, String... needles) {
            if (needles == null || needles.length == 0) return null;
            List<String> needleList = new ArrayList<>();
            for (String n : needles) if (n != null && !n.isBlank()) needleList.add(n.toLowerCase(Locale.ROOT));

            List<ButtonWidget> all = new ArrayList<>();
            java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
            try { collect(scr.children(), all, seen, 0); } catch (Throwable ignored) {}

            // Reflective scan of the screen's `drawables` / `renderables`
            // lists so we also pick up addDrawable widgets that aren't in
            // children() during a resize.
            try {
                for (Class<?> c = scr.getClass(); c != null; c = c.getSuperclass()) {
                    for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                        if (!java.util.List.class.isAssignableFrom(f.getType())) continue;
                        String fn = f.getName().toLowerCase(Locale.ROOT);
                        if (!(fn.contains("drawable") || fn.contains("renderable"))) continue;
                        f.setAccessible(true);
                        Object val = f.get(scr);
                        if (val instanceof java.util.List<?> list) {
                            for (Object o : list) {
                                if (o instanceof ButtonWidget btn && !seen.containsKey(btn)) {
                                    seen.put(btn, Boolean.TRUE);
                                    all.add(btn);
                                } else if (o instanceof net.minecraft.client.gui.ParentElement pe) {
                                    try { collect(pe.children(), all, seen, 0); } catch (Throwable ignored) {}
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}

            for (ButtonWidget btn : all) {
                String label;
                try { label = btn.getMessage().getString(); } catch (Throwable t) { continue; }
                if (label == null || label.isBlank()) continue;
                // Skip our own button so we never anchor to ourselves.
                if (btn instanceof TierTaggerIconButton) continue;
                String lower = label.toLowerCase(Locale.ROOT);
                for (String needle : needleList) {
                    if (lower.contains(needle)) return btn;
                }
            }
            return null;
        }

        private static void collect(java.util.List<? extends Element> in,
                                    java.util.List<ButtonWidget> out,
                                    java.util.IdentityHashMap<Object, Boolean> seen,
                                    int depth) {
            if (in == null || depth > 6) return;
            for (Element el : in) {
                if (el == null || seen.containsKey(el)) continue;
                seen.put(el, Boolean.TRUE);
                if (el instanceof ButtonWidget btn) out.add(btn);
                if (el instanceof net.minecraft.client.gui.ParentElement pe) {
                    try { collect(pe.children(), out, seen, depth + 1); } catch (Throwable ignored) {}
                }
            }
        }
    }
}

package com.outertiers.tiertagger.fabric.mixin;

import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.fabric.compat.Compat;
import com.outertiers.tiertagger.fabric.screen.TierConfigScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
 *   button it locates by text. F11, window resize, DPI / monitor swap can
 *   never leave it in a stale spot — the very next frame re-anchors it to
 *   wherever vanilla now draws Controls. Falls back to the bottom-right
 *   corner if Controls can't be found (modded / future MC) so the
 *   shortcut never disappears.
 *
 *   v1.21.11.51 and earlier placed a wide [TierTagger] button under
 *   Credits & Attribution. That placement has been removed in this
 *   release per user request — the icon-next-to-Controls placement is
 *   tighter, looks native, and matches the OuterTiers branding.
 *
 * NOTE: This class extends Screen at compile time so that the {@code protected}
 * method addRenderableWidget is reachable. At runtime Mixin merges the
 * inject method into OptionsScreen itself, so the {@code extends Screen}
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
            Screen self = (Screen)(Object)this;
            this.addRenderableWidget(new TierTaggerIconButton(self));
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
     */
    public static final class TierTaggerIconButton extends Button {
        private static final ResourceLocation OT_LOGO =
            ResourceLocation.fromNamespaceAndPath("tiertagger", "textures/logo/outertiers.png");
        private static final int SIZE = 20;
        private static final int GAP  = 4;

        private final Screen parentScreen;

        public TierTaggerIconButton(Screen parent) {
            // Initial dimensions are placeholders — render() re-anchors
            // before the first draw, so the user never sees the (4,4) spot.
            super(4, 4, SIZE, SIZE,
                  Component.literal(""),
                  btn -> {
                      Minecraft mc = Minecraft.getInstance();
                      if (mc != null) mc.setScreen(new TierConfigScreen(parent));
                  },
                  DEFAULT_NARRATION);
            this.parentScreen = parent;
            // Tooltip so users know what the icon does on first hover.
            try {
                this.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.literal("\u00a7eTierTagger\u00a7r\nOpen the OuterTiers configuration screen.")));
            } catch (Throwable ignored) {}
            try { anchorToControls(); } catch (Throwable ignored) {}
        }

        @Override
        protected void renderWidget(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
            // Re-anchor BEFORE drawing so the user never sees a flicker
            // at the wrong position after a screen resize.
            try { anchorToControls(); } catch (Throwable ignored) {}

            int x = this.getX(), y = this.getY(), w = this.getWidth(), h = this.getHeight();
            boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;

            // Vanilla-style square frame so the button reads as a native
            // Options-screen control instead of a floating sprite.
            int bg     = hovered ? 0xFF606060 : 0xFF303030;
            int border = hovered ? 0xFFFFFFFF : 0xFFA0A0A0;
            ctx.fill(x,         y,         x + w,     y + h,     bg);
            ctx.fill(x,         y,         x + w,     y + 1,     border);
            ctx.fill(x,         y + h - 1, x + w,     y + h,     border);
            ctx.fill(x,         y,         x + 1,     y + h,     border);
            ctx.fill(x + w - 1, y,         x + w,     y + h,     border);

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
                Component fallback = Component.literal("\u00a7eOT");
                int tw = Minecraft.getInstance().font.width(fallback);
                ctx.drawString(Minecraft.getInstance().font, fallback,
                        x + (w - tw) / 2,
                        y + (h - 8) / 2,
                        0xFFFFFFFF, true);
            }
        }

        private void anchorToControls() {
            Screen scr = parentScreen;
            if (scr == null) return;

            Button controls = findButton(scr,
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

            int targetX = controls.getX() + controls.getWidth() + GAP;
            int targetY = controls.getY() + (controls.getHeight() - SIZE) / 2;

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
        private static Button findButton(Screen scr, String... needles) {
            if (needles == null || needles.length == 0) return null;
            List<String> needleList = new ArrayList<>();
            for (String n : needles) if (n != null && !n.isBlank()) needleList.add(n.toLowerCase(Locale.ROOT));

            List<Button> all = new ArrayList<>();
            java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
            try { collect(scr.children(), all, seen, 0); } catch (Throwable ignored) {}

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
                if (btn instanceof TierTaggerIconButton) continue;
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

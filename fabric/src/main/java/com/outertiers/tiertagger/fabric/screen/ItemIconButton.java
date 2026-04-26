package com.outertiers.tiertagger.fabric.screen;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.function.BooleanSupplier;

/**
 * A {@link ButtonWidget} subclass that renders a vanilla item icon centered
 * in the button face instead of a text label. Optionally dims the icon when
 * an "off" / "disabled-feature" state is reported by {@link #dimmed}.
 *
 * <p>Used by the redesigned <em>Tiers Config</em> tab in
 * {@link TierConfigScreen} for the five action buttons (reload cache,
 * cycle right gamemode, toggle gamemode icon, toggle tab list, toggle chat).
 * The previous version of those buttons rendered Unicode glyphs (\u21BB,
 * \u25C9, \u26ED, \u2630, \u270E) which read as fuzzy white squares on
 * many shader packs / resource packs and didn't match the rest of the mod's
 * icon-driven UI. Item icons render through the vanilla item-renderer so
 * they stay crisp at any GUI scale and pick up the player's chosen pack.
 */
public class ItemIconButton extends ButtonWidget {

    private final ItemStack icon;
    private final BooleanSupplier dimmed;
    /** Optional additional foreground text (e.g. small "ON"/"OFF" badge in the corner). */
    private final Text overlayLabel;

    public ItemIconButton(int x, int y, int w, int h,
                          ItemStack icon,
                          BooleanSupplier dimmed,
                          Text narration,
                          PressAction onPress) {
        this(x, y, w, h, icon, dimmed, null, narration, onPress);
    }

    public ItemIconButton(int x, int y, int w, int h,
                          ItemStack icon,
                          BooleanSupplier dimmed,
                          Text overlayLabel,
                          Text narration,
                          PressAction onPress) {
        super(x, y, w, h, narration == null ? Text.empty() : narration, onPress, DEFAULT_NARRATION_SUPPLIER);
        this.icon = icon == null ? ItemStack.EMPTY : icon;
        this.dimmed = dimmed;
        this.overlayLabel = overlayLabel;
    }

    @Override
    protected void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Suppress the parent's text-label rendering by temporarily setting
        // the message to empty, then restoring. The button background +
        // hover frame are still drawn by super.renderWidget which is exactly
        // what we want.
        Text saved = this.getMessage();
        try { this.setMessage(Text.empty()); } catch (Throwable ignored) {}
        try { super.renderWidget(ctx, mouseX, mouseY, delta); } catch (Throwable ignored) {}
        try { this.setMessage(saved); } catch (Throwable ignored) {}

        try {
            if (icon != null && !icon.isEmpty()) {
                int ix = this.getX() + (this.getWidth()  - 16) / 2;
                int iy = this.getY() + (this.getHeight() - 16) / 2;
                ctx.drawItem(icon, ix, iy);
            }
        } catch (Throwable ignored) {}

        // Greyed overlay when the toggle this button represents is OFF.
        try {
            if (dimmed != null && dimmed.getAsBoolean()) {
                ctx.fill(this.getX() + 1, this.getY() + 1,
                         this.getX() + this.getWidth() - 1,
                         this.getY() + this.getHeight() - 1,
                         0xA00C0F14);
                // A faint red diagonal "/" so users instantly read it as OFF
                // even on busy backgrounds — same affordance YouTube /
                // Discord use for muted-mic toggles.
                int x1 = this.getX() + 4;
                int y1 = this.getY() + this.getHeight() - 5;
                int x2 = this.getX() + this.getWidth() - 4;
                int y2 = this.getY() + 4;
                drawLine(ctx, x1, y1, x2, y2, 0xFFE74C3C);
            }
        } catch (Throwable ignored) {}

        // Optional small badge label (top-right corner).
        try {
            if (overlayLabel != null) {
                net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
                if (mc != null && mc.textRenderer != null) {
                    int tw = mc.textRenderer.getWidth(overlayLabel);
                    int tx = this.getX() + this.getWidth() - tw - 2;
                    int ty = this.getY() + 1;
                    ctx.drawTextWithShadow(mc.textRenderer, overlayLabel, tx, ty, 0xFFFFFFFF);
                }
            }
        } catch (Throwable ignored) {}
    }

    /** Bresenham-style 1-pixel line via per-pixel fills (no GL line state needed). */
    private static void drawLine(DrawContext ctx, int x0, int y0, int x1, int y1, int argb) {
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int x = x0, y = y0;
        for (int safety = 0; safety < 2048; safety++) {
            try { ctx.fill(x, y, x + 1, y + 1, argb); } catch (Throwable ignored) {}
            if (x == x1 && y == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x += sx; }
            if (e2 <= dx) { err += dx; y += sy; }
        }
    }
}

package com.outertiers.tiertagger.fabric.screen;

import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierService;
import com.outertiers.tiertagger.common.TierTaggerCore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Sub-screen launched from {@link TierConfigScreen} that lets the user pick
 * which gamemodes are shown in either the player tab list or above player
 * nametags. The two contexts are independent so a user can, for example, only
 * surface Vanilla in the tab list while still seeing every mode above heads.
 *
 * <p>v1.21.11.28 fix: previously this screen could appear completely empty
 * because (a) it lacked the {@code bgApplied} guard so MC's
 * {@link Screen#render(GuiGraphics, int, int, float)} re-ran
 * {@code renderBackground} after our panel was drawn, blanking the title and
 * panel; and (b) any throw inside the per-mode widget loop aborted the rest
 * of the build, leaving the screen with no buttons. The screen now mirrors
 * {@link TierConfigScreen}'s defensive build pattern (safeAdd + scrolling
 * + render guard) so all ~30 modes are reachable on every screen size.
 */
public class ModeFilterScreen extends Screen {

    private static final int BTN_W   = 150;
    private static final int BTN_H   = 20;
    private static final int BTN_GAP = 6;
    private static final int ROW_H   = BTN_H + 4;

    private static final int PANEL_W_MAX     = 360;
    private static final int BG_PANEL        = 0xF20E1116;
    private static final int BG_PANEL_BORDER = 0xFF2A2F38;
    private static final int BG_HEADER       = 0xFF181C24;
    private static final int FG_FAINT        = 0xFF9AA0AA;

    private final Screen parent;
    /** {@code true} = configuring the tab list, {@code false} = configuring nametags. */
    private final boolean tabContext;
    private boolean bgApplied = false;

    private int scrollY    = 0;
    private int maxScroll  = 0;
    private int bodyTop    = 36;
    private int bodyBottom = 0;
    private int maxRowUsed = 0;
    private volatile String lastInitError = null;

    public ModeFilterScreen(Screen parent, boolean tabContext) {
        super(Component.literal(tabContext ? "Tab Modes" : "Nametag Modes"));
        this.parent = parent;
        this.tabContext = tabContext;
    }

    private int colX(int col) {
        int totalW = BTN_W * 2 + BTN_GAP;
        int startX = (this.width - totalW) / 2;
        return startX + col * (BTN_W + BTN_GAP);
    }

    private int rowY(int row) {
        if (row > maxRowUsed) maxRowUsed = row;
        return bodyTop + 6 + row * ROW_H - scrollY;
    }

    @Override
    protected void init() {
        lastInitError = null;
        maxRowUsed = 0;
        bodyTop    = 36;
        bodyBottom = this.height - 32 - ROW_H;
        try { buildWidgets(); }
        catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] mode-filter init failed", t);
            lastInitError = t.getClass().getSimpleName() + ": " +
                (t.getMessage() == null ? "(no message)" : t.getMessage());
            this.clearChildren();
            this.addDrawableChild(Button.builder(
                    Component.literal("Done"), b -> closeSafely())
                .dimensions(this.width / 2 - 110, this.height - 27, 220, BTN_H).build());
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hd, double vd) {
        int prev = scrollY;
        scrollY = Math.max(0, Math.min(maxScroll, scrollY - (int)(vd * 16)));
        if (scrollY != prev) {
            this.clearChildren();
            try { buildWidgets(); } catch (Throwable ignored) {}
        }
        return true;
    }

    private void safeAdd(String label, Runnable build) {
        try { build.run(); }
        catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] mode-filter widget " + label + " failed", t);
        }
    }

    private void buildWidgets() {
        TierConfig cfg = TierTaggerCore.config();
        if (cfg == null) {
            this.addDrawableChild(Button.builder(Component.literal("Done"), b -> closeSafely())
                .dimensions(this.width / 2 - 75, this.height / 2, 150, BTN_H).build());
            return;
        }

        // Union of every mode any service knows about, plus the modes currently
        // listed in the user's filter (so unknown / future modes still appear).
        Set<String> modes = new LinkedHashSet<>(TierService.allKnownModes());
        List<String> existing = tabContext ? cfg.tabModes : cfg.nametagModes;
        if (existing != null) modes.addAll(existing);
        List<String> ordered = new ArrayList<>(modes);

        for (int i = 0; i < ordered.size(); i++) {
            final String mode = ordered.get(i);
            final int col = i % 2;
            final int row = i / 2;
            final int y   = rowY(row);
            safeAdd("mode:" + mode, () -> {
                boolean enabled = tabContext ? cfg.isTabModeEnabled(mode) : cfg.isNametagModeEnabled(mode);
                this.addDrawableChild(CycleButton.onOffBuilder(enabled)
                    .build(colX(col), y, BTN_W, BTN_H,
                        Component.literal(prettyName(mode)),
                        (b, v) -> {
                            try {
                                if (tabContext) cfg.setTabModeEnabled(mode, v);
                                else            cfg.setNametagModeEnabled(mode, v);
                                cfg.save();
                            } catch (Throwable t) {
                                TierTaggerCore.LOGGER.warn("[TierTagger] mode-filter toggle failed for {}", mode, t);
                            }
                        }));
            });
        }

        // Bottom action row: Reset + Done — anchored so they never scroll.
        int bottomY = this.height - 27;
        safeAdd("reset", () -> this.addDrawableChild(Button.builder(
                Component.literal("Reset (show all)"),
                b -> {
                    try {
                        if (tabContext) cfg.tabModes = null;
                        else            cfg.nametagModes = null;
                        cfg.save();
                    } catch (Throwable ignored) {}
                    Minecraft mc = this.client != null ? this.client : Minecraft.getInstance();
                    if (mc != null) mc.setScreen(new ModeFilterScreen(parent, tabContext));
                })
            .dimensions(colX(0), bottomY, BTN_W, BTN_H).build()));
        safeAdd("done", () -> this.addDrawableChild(Button.builder(
                Component.literal("Done"), b -> closeSafely())
            .dimensions(colX(1), bottomY, BTN_W, BTN_H).build()));

        int contentH = (maxRowUsed + 2) * ROW_H;
        int viewH    = bodyBottom - bodyTop;
        maxScroll = Math.max(0, contentH - viewH);
        if (scrollY > maxScroll) scrollY = maxScroll;
    }

    private static String prettyName(String mode) {
        if (mode == null || mode.isEmpty()) return "?";
        // Most modes are short ASCII; just upper-case the first letter for a tidy label.
        return Character.toUpperCase(mode.charAt(0)) + mode.substring(1).replace('_', ' ');
    }

    private static int rgb(int argb) { return argb & 0xFFFFFF; }

    @Override
    public void renderBackground(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        // CRITICAL: MC's Screen#render calls renderBackground itself, so without
        // this guard the dim background would be drawn AFTER our panel/title in
        // render(), wiping them and producing the "screen shows nothing" bug.
        if (bgApplied) return;
        bgApplied = true;
        try { super.renderBackground(ctx, mouseX, mouseY, delta); } catch (Throwable ignored) {}
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        bgApplied = false;
        try {
            this.renderBackground(ctx, mouseX, mouseY, delta);

            int totalW = BTN_W * 2 + BTN_GAP;
            int panelW = Math.min(PANEL_W_MAX, this.width - 24);
            panelW = Math.max(panelW, totalW + 24);
            int panelX = (this.width - panelW) / 2;
            int panelTop = 8;
            int panelBottomY = this.height - 8;

            // 1. Panel background.
            fillRect(ctx, panelX, panelTop, panelX + panelW, panelBottomY, BG_PANEL);
            outlineRect(ctx, panelX, panelTop, panelW, panelBottomY - panelTop, BG_PANEL_BORDER);

            // 2. Widgets — drawn outside scissor so the bottom action row is
            //    never clipped. The title strip is re-painted on top below.
            super.render(ctx, mouseX, mouseY, delta);

            // 3. Re-draw the title strip on top to cover any scrolled widget
            //    that crept above bodyTop.
            fillRect(ctx, panelX + 1, panelTop + 1, panelX + panelW - 1, bodyTop, BG_HEADER);
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                this.title.copy().formatted(ChatFormatting.WHITE, ChatFormatting.BOLD),
                this.width / 2, panelTop + 10, 0xFFFFFFFF);
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                Component.literal(tabContext
                        ? "Choose which gamemodes appear in the tab list"
                        : "Choose which gamemodes appear above nametags")
                    .withColor(rgb(FG_FAINT)),
                this.width / 2, panelTop + 18, FG_FAINT);

            // 4. Scroll indicator.
            if (maxScroll > 0) {
                int trackX  = panelX + panelW - 5;
                int trackTop = bodyTop;
                int trackH  = bodyBottom - bodyTop;
                fillRect(ctx, trackX, trackTop, trackX + 3, trackTop + trackH, 0x40FFFFFF);
                int thumbH = Math.max(20, trackH * trackH / Math.max(1, trackH + maxScroll));
                int thumbY = trackTop + (int)((long)(trackH - thumbH) * scrollY / Math.max(1, maxScroll));
                fillRect(ctx, trackX, thumbY, trackX + 3, thumbY + thumbH, 0xFFAAAAAA);
            }

            if (lastInitError != null) {
                drawErrorOverlay(ctx, "Mode-filter init failed", lastInitError);
            }
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] mode-filter render", t);
            try { drawErrorOverlay(ctx, "Mode-filter render failed",
                t.getClass().getSimpleName() + ": " +
                (t.getMessage() == null ? "(no message)" : t.getMessage())); }
            catch (Throwable ignored) {}
        }
    }

    private void drawErrorOverlay(GuiGraphics ctx, String title, String detail) {
        int cx = this.width / 2;
        int cy = this.height / 2;
        int w  = Math.min(this.width - 40, 360);
        fillRect(ctx, cx - w / 2, cy - 30, cx + w / 2, cy + 30, 0xCC110000);
        outlineRect(ctx, cx - w / 2, cy - 30, w, 60, 0xFFFF5555);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
            Component.literal(title).formatted(ChatFormatting.RED, ChatFormatting.BOLD), cx, cy - 18, 0xFFFF5555);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
            Component.literal(detail).formatted(ChatFormatting.WHITE), cx, cy - 4, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
            Component.literal("See latest.log for the full stack trace").formatted(ChatFormatting.GRAY),
            cx, cy + 12, 0xFFAAAAAA);
    }

    private static void fillRect(GuiGraphics ctx, int x1, int y1, int x2, int y2, int argb) {
        try { ctx.fill(x1, y1, x2, y2, argb); } catch (Throwable ignored) {}
    }

    private static void outlineRect(GuiGraphics ctx, int x, int y, int w, int h, int argb) {
        try {
            ctx.fill(x,         y,         x + w,     y + 1,     argb);
            ctx.fill(x,         y + h - 1, x + w,     y + h,     argb);
            ctx.fill(x,         y,         x + 1,     y + h,     argb);
            ctx.fill(x + w - 1, y,         x + w,     y + h,     argb);
        } catch (Throwable ignored) {}
    }

    private void closeSafely() {
        Minecraft mc = this.client != null ? this.client : Minecraft.getInstance();
        if (mc != null) mc.setScreen(parent);
    }

    @Override
    public void close() { closeSafely(); }
}

package com.outertiers.tiertagger.fabric.screen;

import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierService;
import com.outertiers.tiertagger.common.TierTaggerCore;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Sub-screen launched from {@link TierConfigScreen} that lets the user pick
 * which gamemodes are shown in either the player tab list or above player
 * nametags. The two contexts are independent so a user can, for example, only
 * surface Vanilla in the tab list while still seeing every mode above heads.
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

    public ModeFilterScreen(Screen parent, boolean tabContext) {
        super(Text.literal(tabContext ? "Tab Modes" : "Nametag Modes"));
        this.parent = parent;
        this.tabContext = tabContext;
    }

    private int colX(int col) {
        int totalW = BTN_W * 2 + BTN_GAP;
        int startX = (this.width - totalW) / 2;
        return startX + col * (BTN_W + BTN_GAP);
    }

    private int rowY(int row) {
        return Math.max(48, this.height / 8) + row * ROW_H;
    }

    private static int rgb(int argb) { return argb & 0xFFFFFF; }

    @Override
    protected void init() {
        TierConfig cfg = TierTaggerCore.config();
        if (cfg == null) {
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> closeSafely())
                .dimensions(this.width / 2 - 75, this.height / 2, 150, BTN_H).build());
            return;
        }

        // Union of every mode any service knows about, plus the modes currently
        // listed in the user's filter (so unknown / future modes still appear).
        Set<String> modes = new LinkedHashSet<>(TierService.allKnownModes());
        List<String> existing = tabContext ? cfg.tabModes : cfg.nametagModes;
        if (existing != null) modes.addAll(existing);
        List<String> ordered = new ArrayList<>(modes);

        int bottomLimit = this.height - 32;
        for (int i = 0; i < ordered.size(); i++) {
            final String mode = ordered.get(i);
            final int col = i % 2;
            final int row = i / 2;
            int y = rowY(row);
            if (y + BTN_H > bottomLimit) break;

            boolean enabled = tabContext ? cfg.isTabModeEnabled(mode) : cfg.isNametagModeEnabled(mode);
            this.addDrawableChild(CyclingButtonWidget.onOffBuilder(enabled)
                .build(colX(col), y, BTN_W, BTN_H,
                    Text.literal(prettyName(mode)),
                    (b, v) -> {
                        try {
                            if (tabContext) cfg.setTabModeEnabled(mode, v);
                            else            cfg.setNametagModeEnabled(mode, v);
                            cfg.save();
                        } catch (Throwable t) {
                            TierTaggerCore.LOGGER.warn("[TierTagger] mode-filter toggle failed for {}", mode, t);
                        }
                    }));
        }

        // Bottom action row: Reset + Done.
        int bottomY = this.height - 27;
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Reset (show all)"),
                b -> {
                    try {
                        if (tabContext) cfg.tabModes = null;
                        else            cfg.nametagModes = null;
                        cfg.save();
                    } catch (Throwable ignored) {}
                    MinecraftClient mc = this.client != null ? this.client : MinecraftClient.getInstance();
                    if (mc != null) mc.setScreen(new ModeFilterScreen(parent, tabContext));
                })
            .dimensions(colX(0), bottomY, BTN_W, BTN_H).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> closeSafely())
            .dimensions(colX(1), bottomY, BTN_W, BTN_H).build());
    }

    private static String prettyName(String mode) {
        if (mode == null || mode.isEmpty()) return "?";
        // Most modes are short ASCII; just upper-case the first letter for a tidy label.
        return Character.toUpperCase(mode.charAt(0)) + mode.substring(1);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        try {
            this.renderBackground(ctx, mouseX, mouseY, delta);

            int totalW = BTN_W * 2 + BTN_GAP;
            int panelW = Math.min(PANEL_W_MAX, this.width - 24);
            panelW = Math.max(panelW, totalW + 24);
            int panelX = (this.width - panelW) / 2;
            int panelTop = 8;
            int panelBottom = this.height - 8;
            fillRect(ctx, panelX, panelTop, panelX + panelW, panelBottom, BG_PANEL);
            outlineRect(ctx, panelX, panelTop, panelW, panelBottom - panelTop, BG_PANEL_BORDER);

            fillRect(ctx, panelX + 1, panelTop + 1, panelX + panelW - 1, panelTop + 26, BG_HEADER);
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                this.title.copy().formatted(Formatting.WHITE, Formatting.BOLD),
                this.width / 2, panelTop + 10, 0xFFFFFFFF);
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(tabContext
                        ? "Choose which gamemodes appear in the tab list"
                        : "Choose which gamemodes appear above nametags")
                    .withColor(rgb(FG_FAINT)),
                this.width / 2, panelTop + 18, FG_FAINT);

            super.render(ctx, mouseX, mouseY, delta);
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] mode-filter render", t);
        }
    }

    private static void fillRect(DrawContext ctx, int x1, int y1, int x2, int y2, int argb) {
        try { ctx.fill(x1, y1, x2, y2, argb); } catch (Throwable ignored) {}
    }

    private static void outlineRect(DrawContext ctx, int x, int y, int w, int h, int argb) {
        try {
            ctx.fill(x,         y,         x + w,     y + 1,     argb);
            ctx.fill(x,         y + h - 1, x + w,     y + h,     argb);
            ctx.fill(x,         y,         x + 1,     y + h,     argb);
            ctx.fill(x + w - 1, y,         x + w,     y + h,     argb);
        } catch (Throwable ignored) {}
    }

    private void closeSafely() {
        MinecraftClient mc = this.client != null ? this.client : MinecraftClient.getInstance();
        if (mc != null) mc.setScreen(parent);
    }

    @Override
    public void close() { closeSafely(); }
}

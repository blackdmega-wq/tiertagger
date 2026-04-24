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

import java.util.Arrays;
import java.util.List;

/**
 * TierTagger settings screen.
 *
 * v1.7 redesign:
 *   - Solid panel background so the widgets are always legible regardless of
 *     what's behind the GUI.
 *   - Compact 2-column layout that always fits in 480p.
 *   - Per-service toggles live in their own clearly-labelled section.
 *   - Defensive {@code init()} so a single bad widget can't crash the screen.
 *
 * Crash fix in v1.6.0 (preserved): {@code Style.withColor(int)} now requires a
 * pure RGB triplet (0..0xFFFFFF). Every {@code .withColor} call masks the
 * accent argb with {@code & 0xFFFFFF} so 1.21.5+ doesn't reject it.
 */
public class TierConfigScreen extends Screen {

    private static final int BTN_W   = 150;
    private static final int BTN_H   = 20;
    private static final int BTN_GAP = 6;
    private static final int ROW_H   = BTN_H + 4;

    private static final int PANEL_W_MAX     = 360;
    private static final int BG_PANEL        = 0xF20E1116;
    private static final int BG_PANEL_BORDER = 0xFF2A2F38;
    private static final int BG_HEADER       = 0xFF181C24;
    private static final int FG_FAINT        = 0x9AA0AA;

    private final Screen parent;
    private boolean bgApplied = false;

    /** y position where the per-service section starts (used by render()). */
    private int servicesHeaderY = -1;

    public TierConfigScreen(Screen parent) {
        super(Text.literal("TierTagger \u2014 Settings"));
        this.parent = parent;
    }

    private int colX(int col) {
        int totalW = BTN_W * 2 + BTN_GAP;
        int startX = (this.width - totalW) / 2;
        return startX + col * (BTN_W + BTN_GAP);
    }

    private int rowY(int row) {
        return Math.max(40, this.height / 8) + row * ROW_H;
    }

    private static int rgb(int argb) { return argb & 0xFFFFFF; }

    @Override
    protected void init() {
        try { buildWidgets(); } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] config screen init failed: {}", t.toString());
            this.clearChildren();
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Done (init error \u2014 see log)"), b -> closeSafely())
                .dimensions(this.width / 2 - 110, this.height / 2, 220, BTN_H).build());
        }
    }

    private void buildWidgets() {
        TierConfig cfg = TierTaggerCore.config();
        if (cfg == null) {
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> closeSafely())
                .dimensions(this.width / 2 - 75, this.height / 2, 150, BTN_H).build());
            return;
        }

        int r = 0;

        // ── Left / right badge service ──
        this.addDrawableChild(
            CyclingButtonWidget.<TierService>builder(s -> Text.literal(s.displayName).withColor(rgb(s.accentArgb)))
                .values(TierService.values())
                .initially(cfg.leftServiceEnum())
                .build(colX(0), rowY(r), BTN_W, BTN_H,
                    Text.literal("Left Badge"),
                    (b, v) -> { cfg.leftService = v.id; cfg.save(); }));
        this.addDrawableChild(
            CyclingButtonWidget.<TierService>builder(s -> Text.literal(s.displayName).withColor(rgb(s.accentArgb)))
                .values(TierService.values())
                .initially(cfg.rightServiceEnum())
                .build(colX(1), rowY(r), BTN_W, BTN_H,
                    Text.literal("Right Badge"),
                    (b, v) -> { cfg.rightService = v.id; cfg.save(); }));
        r++;

        // ── Tab / Nametag ──
        this.addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.showInTab)
            .build(colX(0), rowY(r), BTN_W, BTN_H, Text.literal("Tab Badges"),
                (b, v) -> { cfg.showInTab = v; cfg.save(); }));
        this.addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.showNametag)
            .build(colX(1), rowY(r), BTN_W, BTN_H, Text.literal("Nametag"),
                (b, v) -> { cfg.showNametag = v; cfg.save(); }));
        r++;

        // ── Dual badges / Coloured ──
        this.addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.rightBadgeEnabled)
            .build(colX(0), rowY(r), BTN_W, BTN_H, Text.literal("Dual Badges"),
                (b, v) -> { cfg.rightBadgeEnabled = v; cfg.save(); }));
        this.addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.coloredBadges)
            .build(colX(1), rowY(r), BTN_W, BTN_H, Text.literal("Coloured Badges"),
                (b, v) -> { cfg.coloredBadges = v; cfg.save(); }));
        r++;

        // ── Service tag / Mode icons ──
        this.addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.showServiceIcon)
            .build(colX(0), rowY(r), BTN_W, BTN_H, Text.literal("Service Tag"),
                (b, v) -> { cfg.showServiceIcon = v; cfg.save(); }));
        this.addDrawableChild(CyclingButtonWidget.onOffBuilder(!cfg.disableIcons)
            .build(colX(1), rowY(r), BTN_W, BTN_H, Text.literal("Mode Icons"),
                (b, v) -> { cfg.disableIcons = !v; cfg.showModeIcon = v; cfg.save(); }));
        r++;

        // ── Peak tier / Badge format ──
        this.addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.showPeak)
            .build(colX(0), rowY(r), BTN_W, BTN_H, Text.literal("Peak Tier"),
                (b, v) -> { cfg.showPeak = v; cfg.save(); }));
        List<String> formats = Arrays.asList(TierConfig.BADGE_FORMATS);
        String initialFormat = (cfg.badgeFormat == null || !formats.contains(cfg.badgeFormat))
            ? "bracket" : cfg.badgeFormat;
        this.addDrawableChild(
            CyclingButtonWidget.<String>builder(Text::literal)
                .values(formats)
                .initially(initialFormat)
                .build(colX(1), rowY(r), BTN_W, BTN_H,
                    Text.literal("Badge Format"),
                    (b, v) -> { cfg.badgeFormat = v; cfg.save(); }));
        r++;

        // Section break before per-service toggles
        servicesHeaderY = rowY(r) + 6;
        r++;

        // ── Per-service enable toggles (2 per row) ──
        TierService[] svcs = TierService.values();
        int bottomLimit = this.height - 32 - ROW_H;  // leave room for the bottom buttons
        for (int i = 0; i < svcs.length; i++) {
            TierService svc = svcs[i];
            int col = i % 2;
            if (col == 0 && i > 0) r++;
            if (rowY(r) + BTN_H > bottomLimit) break;
            this.addDrawableChild(
                CyclingButtonWidget.onOffBuilder(cfg.isServiceEnabled(svc))
                    .build(colX(col), rowY(r), BTN_W, BTN_H,
                        Text.literal(svc.displayName).withColor(rgb(svc.accentArgb)),
                        (b, v) -> {
                            cfg.setServiceEnabled(svc, v);
                            cfg.save();
                            try { TierTaggerCore.cache().invalidate(); } catch (Throwable ignored) {}
                        }));
        }

        // ── Bottom buttons (always anchored to the bottom of the screen) ──
        int bottomY = this.height - 27;
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Refresh Cache"),
                b -> { try { TierTaggerCore.cache().invalidate(); } catch (Throwable ignored) {} })
            .dimensions(colX(0), bottomY, BTN_W, BTN_H).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> closeSafely())
            .dimensions(colX(1), bottomY, BTN_W, BTN_H).build());
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (bgApplied) return;
        bgApplied = true;
        try { super.renderBackground(ctx, mouseX, mouseY, delta); } catch (Throwable ignored) {}
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        bgApplied = false;
        try {
            this.renderBackground(ctx, mouseX, mouseY, delta);

            // Solid centred backdrop so widgets stay legible.
            int totalW = BTN_W * 2 + BTN_GAP;
            int panelW = Math.min(PANEL_W_MAX, this.width - 24);
            panelW = Math.max(panelW, totalW + 24);
            int panelX = (this.width - panelW) / 2;
            int panelTop = 8;
            int panelBottom = this.height - 8;
            fillRect(ctx, panelX, panelTop, panelX + panelW, panelBottom, BG_PANEL);
            outlineRect(ctx, panelX, panelTop, panelW, panelBottom - panelTop, BG_PANEL_BORDER);

            // Title strip
            fillRect(ctx, panelX + 1, panelTop + 1, panelX + panelW - 1, panelTop + 26, BG_HEADER);
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                this.title.copy().formatted(Formatting.WHITE, Formatting.BOLD),
                this.width / 2, panelTop + 10, 0xFFFFFFFF);
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("v" + TierTaggerCore.MOD_VERSION + "  \u00B7  /tiertagger help for chat commands")
                    .withColor(FG_FAINT),
                this.width / 2, panelTop + 18, FG_FAINT);

            super.render(ctx, mouseX, mouseY, delta);

            if (servicesHeaderY > 0 && servicesHeaderY < this.height - 40) {
                ctx.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("\u2014 Enabled Services \u2014").formatted(Formatting.YELLOW),
                    this.width / 2, servicesHeaderY, 0xFFAA00);
            }
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] config screen render: {}", t.toString());
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

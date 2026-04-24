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

/**
 * TierTagger settings screen styled after Minecraft's Video Settings — two
 * columns of option buttons with a "Done" button at the bottom.
 *
 * Can be opened via:
 *   /tiertagger config
 *   Mod Menu → TierTagger → Configure
 *   Keybind (default K)
 */
public class TierConfigScreen extends Screen {

    private static final int BTN_W   = 150;
    private static final int BTN_H   = 20;
    private static final int BTN_GAP = 4;
    private static final int ROW_H   = BTN_H + 4;

    private final Screen parent;
    private boolean bgApplied = false;

    public TierConfigScreen(Screen parent) {
        super(Text.literal("TierTagger — Settings"));
        this.parent = parent;
    }

    /** X position of the left column (centred on screen). */
    private int colX(int col) {
        int totalW = BTN_W * 2 + BTN_GAP;
        int startX = (this.width - totalW) / 2;
        return startX + col * (BTN_W + BTN_GAP);
    }

    private int rowY(int row) {
        return this.height / 6 + row * ROW_H;
    }

    @Override
    protected void init() {
        TierConfig cfg = TierTaggerCore.config();
        if (cfg == null) {
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> closeSafely())
                .dimensions(this.width / 2 - 75, this.height / 2, 150, BTN_H).build());
            return;
        }

        int r = 0;

        // ── Left / right badge service ──
        this.addDrawableChild(
            CyclingButtonWidget.<TierService>builder(s -> Text.literal(s.displayName).withColor(s.accentArgb))
                .values(TierService.values())
                .initially(cfg.leftServiceEnum())
                .build(colX(0), rowY(r), BTN_W, BTN_H,
                    Text.literal("Left Badge"),
                    (b, v) -> { cfg.leftService = v.id; cfg.save(); }));
        this.addDrawableChild(
            CyclingButtonWidget.<TierService>builder(s -> Text.literal(s.displayName).withColor(s.accentArgb))
                .values(TierService.values())
                .initially(cfg.rightServiceEnum())
                .build(colX(1), rowY(r), BTN_W, BTN_H,
                    Text.literal("Right Badge"),
                    (b, v) -> { cfg.rightService = v.id; cfg.save(); }));
        r++;

        // ── Tab / Nametag badges ──
        this.addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.showInTab)
            .build(colX(0), rowY(r), BTN_W, BTN_H, Text.literal("Tab Badges"),
                (b, v) -> { cfg.showInTab = v; cfg.save(); }));
        this.addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.showNametag)
            .build(colX(1), rowY(r), BTN_W, BTN_H, Text.literal("Nametag"),
                (b, v) -> { cfg.showNametag = v; cfg.save(); }));
        r++;

        // ── Right badge enabled / Coloured ──
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
        this.addDrawableChild(
            CyclingButtonWidget.<String>builder(Text::literal)
                .values(TierConfig.BADGE_FORMATS)
                .initially(cfg.badgeFormat == null ? "bracket" : cfg.badgeFormat)
                .build(colX(1), rowY(r), BTN_W, BTN_H,
                    Text.literal("Badge Format"),
                    (b, v) -> { cfg.badgeFormat = v; cfg.save(); }));
        r++;

        // ── Gap row before services section ──
        r++;

        // ── Per-service enable toggles (2 per row) ──
        TierService[] svcs = TierService.values();
        for (int i = 0; i < svcs.length; i++) {
            TierService svc = svcs[i];
            int col = i % 2;
            if (col == 0 && i > 0) r++;
            if (rowY(r) + BTN_H > this.height - 32) break;
            this.addDrawableChild(
                CyclingButtonWidget.onOffBuilder(cfg.isServiceEnabled(svc))
                    .build(colX(col), rowY(r), BTN_W, BTN_H,
                        Text.literal(svc.displayName).withColor(svc.accentArgb),
                        (b, v) -> {
                            cfg.setServiceEnabled(svc, v);
                            cfg.save();
                            try { TierTaggerCore.cache().invalidate(); } catch (Throwable ignored) {}
                        }));
        }
        if (svcs.length % 2 == 1) r++;
        r++;

        // ── Bottom buttons ──
        int bottomY = this.height - 27;
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Refresh Cache"),
                b -> { try { TierTaggerCore.cache().invalidate(); } catch (Throwable ignored) {} })
            .dimensions(colX(0), bottomY, BTN_W, BTN_H).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> closeSafely())
            .dimensions(colX(1), bottomY, BTN_W, BTN_H).build());
    }

    // Blur-safe guard — prevents "can only blur once per frame" in MC 1.21.x
    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (bgApplied) return;
        bgApplied = true;
        super.renderBackground(ctx, mouseX, mouseY, delta);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        bgApplied = false;
        this.renderBackground(ctx, mouseX, mouseY, delta); // exactly once
        super.render(ctx, mouseX, mouseY, delta);           // widgets; guard blocks any 2nd blur call

        // Title
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, 0xFFFFFF);

        // Services section separator label
        int svcHeaderY = rowY(5) + 3;
        if (svcHeaderY < this.height - 40) {
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("— Enabled Services —").formatted(Formatting.YELLOW),
                this.width / 2, svcHeaderY, 0xFFAA00);
        }
    }

    private void closeSafely() {
        MinecraftClient mc = this.client != null ? this.client : MinecraftClient.getInstance();
        if (mc != null) mc.setScreen(parent);
    }

    @Override
    public void close() { closeSafely(); }
}

package com.outertiers.tiertagger.neoforge.screen;

import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierService;
import com.outertiers.tiertagger.common.TierTaggerCore;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.List;

/**
 * TierTagger settings screen (NeoForge).
 *
 * Crash fix in v1.6.0: every {@code .withColor(int)} call now masks with
 * {@code & 0xFFFFFF} so an alpha-byte-set value can't trip the MC 1.21.5+
 * RGB-range validator. {@code init()} also wrapped in try/catch.
 */
public class TierConfigScreen extends Screen {

    private static final int BTN_W   = 150;
    private static final int BTN_H   = 20;
    private static final int BTN_GAP = 6;
    private static final int ROW_H   = BTN_H + 4;

    private final Screen parent;
    private boolean bgApplied = false;

    public TierConfigScreen(Screen parent) {
        super(Component.literal("TierTagger — Settings"));
        this.parent = parent;
    }

    private int colX(int col) {
        int totalW = BTN_W * 2 + BTN_GAP;
        int startX = (this.width - totalW) / 2;
        return startX + col * (BTN_W + BTN_GAP);
    }

    private int rowY(int row) {
        return Math.max(36, this.height / 6) + row * ROW_H;
    }

    private static int rgb(int argb) { return argb & 0xFFFFFF; }

    @Override
    protected void init() {
        try { buildWidgets(); } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] config screen init failed: {}", t.toString());
            this.clearWidgets();
            this.addRenderableWidget(Button.builder(
                    Component.literal("Done (init error — see log)"), b -> closeSafely())
                .bounds(this.width / 2 - 110, this.height / 2, 220, BTN_H).build());
        }
    }

    private void buildWidgets() {
        TierConfig cfg = TierTaggerCore.config();
        if (cfg == null) {
            this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> closeSafely())
                .bounds(this.width / 2 - 75, this.height / 2, 150, BTN_H).build());
            return;
        }

        int r = 0;

        this.addRenderableWidget(
            CycleButton.<TierService>builder(s -> Component.literal(s.displayName).withColor(rgb(s.accentArgb)))
                .withValues(TierService.values())
                .withInitialValue(cfg.leftServiceEnum())
                .create(colX(0), rowY(r), BTN_W, BTN_H,
                    Component.literal("Left Badge"),
                    (b, v) -> { cfg.leftService = v.id; cfg.save(); }));
        this.addRenderableWidget(
            CycleButton.<TierService>builder(s -> Component.literal(s.displayName).withColor(rgb(s.accentArgb)))
                .withValues(TierService.values())
                .withInitialValue(cfg.rightServiceEnum())
                .create(colX(1), rowY(r), BTN_W, BTN_H,
                    Component.literal("Right Badge"),
                    (b, v) -> { cfg.rightService = v.id; cfg.save(); }));
        r++;

        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.showInTab)
            .create(colX(0), rowY(r), BTN_W, BTN_H, Component.literal("Tab Badges"),
                (b, v) -> { cfg.showInTab = v; cfg.save(); }));
        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.showNametag)
            .create(colX(1), rowY(r), BTN_W, BTN_H, Component.literal("Nametag"),
                (b, v) -> { cfg.showNametag = v; cfg.save(); }));
        r++;

        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.rightBadgeEnabled)
            .create(colX(0), rowY(r), BTN_W, BTN_H, Component.literal("Dual Badges"),
                (b, v) -> { cfg.rightBadgeEnabled = v; cfg.save(); }));
        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.coloredBadges)
            .create(colX(1), rowY(r), BTN_W, BTN_H, Component.literal("Coloured Badges"),
                (b, v) -> { cfg.coloredBadges = v; cfg.save(); }));
        r++;

        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.showServiceIcon)
            .create(colX(0), rowY(r), BTN_W, BTN_H, Component.literal("Service Tag"),
                (b, v) -> { cfg.showServiceIcon = v; cfg.save(); }));
        this.addRenderableWidget(CycleButton.onOffBuilder(!cfg.disableIcons)
            .create(colX(1), rowY(r), BTN_W, BTN_H, Component.literal("Mode Icons"),
                (b, v) -> { cfg.disableIcons = !v; cfg.showModeIcon = v; cfg.save(); }));
        r++;

        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.showPeak)
            .create(colX(0), rowY(r), BTN_W, BTN_H, Component.literal("Peak Tier"),
                (b, v) -> { cfg.showPeak = v; cfg.save(); }));
        List<String> formats = Arrays.asList(TierConfig.BADGE_FORMATS);
        String initialFormat = (cfg.badgeFormat == null || !formats.contains(cfg.badgeFormat))
            ? "bracket" : cfg.badgeFormat;
        this.addRenderableWidget(
            CycleButton.<String>builder(Component::literal)
                .withValues(formats)
                .withInitialValue(initialFormat)
                .create(colX(1), rowY(r), BTN_W, BTN_H,
                    Component.literal("Badge Format"),
                    (b, v) -> { cfg.badgeFormat = v; cfg.save(); }));
        r += 2;

        TierService[] svcs = TierService.values();
        for (int i = 0; i < svcs.length; i++) {
            TierService svc = svcs[i];
            int col = i % 2;
            if (col == 0 && i > 0) r++;
            if (rowY(r) + BTN_H > this.height - 32) break;
            this.addRenderableWidget(
                CycleButton.onOffBuilder(cfg.isServiceEnabled(svc))
                    .create(colX(col), rowY(r), BTN_W, BTN_H,
                        Component.literal(svc.displayName).withColor(rgb(svc.accentArgb)),
                        (b, v) -> {
                            cfg.setServiceEnabled(svc, v);
                            cfg.save();
                            try { TierTaggerCore.cache().invalidate(); } catch (Throwable ignored) {}
                        }));
        }
        if (svcs.length % 2 == 1) r++;
        r++;

        int bottomY = this.height - 27;
        this.addRenderableWidget(Button.builder(
                Component.literal("Refresh Cache"),
                b -> { try { TierTaggerCore.cache().invalidate(); } catch (Throwable ignored) {} })
            .bounds(colX(0), bottomY, BTN_W, BTN_H).build());
        this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> closeSafely())
            .bounds(colX(1), bottomY, BTN_W, BTN_H).build());
    }

    @Override
    public void renderBackground(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        if (bgApplied) return;
        bgApplied = true;
        try { super.renderBackground(ctx, mouseX, mouseY, delta); } catch (Throwable ignored) {}
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        bgApplied = false;
        try {
            this.renderBackground(ctx, mouseX, mouseY, delta);
            super.render(ctx, mouseX, mouseY, delta);

            ctx.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFF);
            int svcHeaderY = rowY(5) + 3;
            if (svcHeaderY < this.height - 40) {
                ctx.drawCenteredString(this.font,
                    Component.literal("— Enabled Services —").withStyle(ChatFormatting.YELLOW),
                    this.width / 2, svcHeaderY, 0xFFAA00);
            }
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] config screen render: {}", t.toString());
        }
    }

    private void closeSafely() {
        Minecraft mc = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        if (mc != null) mc.setScreen(parent);
    }

    @Override
    public void onClose() { closeSafely(); }
}

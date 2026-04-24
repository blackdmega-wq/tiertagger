package com.outertiers.tiertagger.fabric.screen;

import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierTaggerCore;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lightweight settings screen — no third-party UI dependency.
 * Open with {@code /tiertagger config}. Defensive against malformed config values
 * (e.g. an old config file with a gamemode that is no longer in {@link TierConfig#GAMEMODES}).
 */
public class TierConfigScreen extends Screen {
    private final Screen parent;

    public TierConfigScreen(Screen parent) {
        super(Text.literal("TierTagger – Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        TierConfig cfg = TierTaggerCore.config();
        if (cfg == null) {
            // Should never happen, but guard against an init-order race rather than crash the GUI.
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"),
                    btn -> closeSafely())
                .dimensions(this.width / 2 - 100, this.height / 2, 200, 20).build());
            return;
        }

        int cx = this.width / 2;
        int y  = this.height / 6;
        int rowH = 24;
        int btnW = 220;

        // Normalise gamemode so CyclingButtonWidget.initially() never throws.
        List<String> gamemodes = new ArrayList<>();
        for (String g : TierConfig.GAMEMODES) gamemodes.add(g);
        String currentMode = (cfg.gamemode == null) ? "overall" : cfg.gamemode.toLowerCase(Locale.ROOT);
        if (!gamemodes.contains(currentMode)) {
            currentMode = "overall";
            cfg.gamemode = "overall";
            try { cfg.save(); } catch (Throwable ignored) {}
        }
        final String initialMode = currentMode;

        try {
            this.addDrawableChild(CyclingButtonWidget.<String>builder(g -> Text.literal(g))
                .values(gamemodes)
                .initially(initialMode)
                .build(cx - btnW / 2, y, btnW, 20,
                    Text.literal("Gamemode"),
                    (btn, value) -> {
                        cfg.gamemode = value;
                        try { cfg.save(); } catch (Throwable ignored) {}
                        try { TierTaggerCore.cache().invalidate(); } catch (Throwable ignored) {}
                    }));
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] gamemode cycler init failed: {}", t.toString());
        }

        y += rowH;
        this.addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.showInTab)
            .build(cx - btnW / 2, y, btnW, 20,
                Text.literal("Tab list badges"),
                (btn, value) -> { cfg.showInTab = value; try { cfg.save(); } catch (Throwable ignored) {} }));

        y += rowH;
        this.addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.showNametag)
            .build(cx - btnW / 2, y, btnW, 20,
                Text.literal("Nametag badges (above head)"),
                (btn, value) -> { cfg.showNametag = value; try { cfg.save(); } catch (Throwable ignored) {} }));

        y += rowH;
        this.addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.showPeak)
            .build(cx - btnW / 2, y, btnW, 20,
                Text.literal("Show peak tier"),
                (btn, value) -> {
                    cfg.showPeak = value;
                    try { cfg.save(); } catch (Throwable ignored) {}
                    try { TierTaggerCore.cache().invalidate(); } catch (Throwable ignored) {}
                }));

        y += rowH;
        this.addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.fallthroughToHighest)
            .build(cx - btnW / 2, y, btnW, 20,
                Text.literal("Fall-through to highest tier"),
                (btn, value) -> { cfg.fallthroughToHighest = value; try { cfg.save(); } catch (Throwable ignored) {} }));

        y += rowH;
        this.addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.coloredBadges)
            .build(cx - btnW / 2, y, btnW, 20,
                Text.literal("Coloured badges"),
                (btn, value) -> { cfg.coloredBadges = value; try { cfg.save(); } catch (Throwable ignored) {} }));

        y += rowH + 8;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Refresh cache"),
                btn -> { try { TierTaggerCore.cache().invalidate(); } catch (Throwable ignored) {} })
            .dimensions(cx - btnW / 2, y, btnW, 20).build());

        y += rowH + 16;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"),
                btn -> closeSafely())
            .dimensions(cx - 100, y, 200, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        try {
            this.renderBackground(ctx, mouseX, mouseY, delta);
            super.render(ctx, mouseX, mouseY, delta);
            ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 16, 0xFFFFFF);
            TierConfig cfg = TierTaggerCore.config();
            String api = (cfg == null || cfg.apiBase == null) ? "?" : cfg.apiBase;
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("API: " + api).formatted(Formatting.DARK_GRAY),
                this.width / 2, this.height - 18, 0x888888);
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] config screen render failed: {}", t.toString());
        }
    }

    private void closeSafely() {
        MinecraftClient mc = (this.client != null) ? this.client : MinecraftClient.getInstance();
        if (mc != null) mc.setScreen(parent);
    }

    @Override
    public void close() { closeSafely(); }
}

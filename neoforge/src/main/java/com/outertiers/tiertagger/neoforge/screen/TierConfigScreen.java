package com.outertiers.tiertagger.neoforge.screen;

import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierTaggerCore;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TierConfigScreen extends Screen {
    private final Screen parent;

    public TierConfigScreen(Screen parent) {
        super(Component.literal("TierTagger – Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        TierConfig cfg = TierTaggerCore.config();
        if (cfg == null) {
            this.addRenderableWidget(Button.builder(Component.literal("Close"),
                    btn -> closeSafely())
                .bounds(this.width / 2 - 100, this.height / 2, 200, 20).build());
            return;
        }

        int cx = this.width / 2;
        int y  = this.height / 6;
        int rowH = 24;
        int btnW = 220;

        // Normalise gamemode so CycleButton.withInitialValue() never throws.
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
            this.addRenderableWidget(CycleButton.<String>builder(g -> Component.literal(g))
                .withValues(gamemodes)
                .withInitialValue(initialMode)
                .create(cx - btnW / 2, y, btnW, 20,
                    Component.literal("Gamemode"),
                    (btn, value) -> {
                        cfg.gamemode = value;
                        try { cfg.save(); } catch (Throwable ignored) {}
                        try { TierTaggerCore.cache().invalidate(); } catch (Throwable ignored) {}
                    }));
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] gamemode cycler init failed: {}", t.toString());
        }

        y += rowH;
        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.showInTab)
            .create(cx - btnW / 2, y, btnW, 20,
                Component.literal("Tab list badges"),
                (btn, value) -> { cfg.showInTab = value; try { cfg.save(); } catch (Throwable ignored) {} }));

        y += rowH;
        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.showNametag)
            .create(cx - btnW / 2, y, btnW, 20,
                Component.literal("Nametag badges (above head)"),
                (btn, value) -> { cfg.showNametag = value; try { cfg.save(); } catch (Throwable ignored) {} }));

        y += rowH;
        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.showPeak)
            .create(cx - btnW / 2, y, btnW, 20,
                Component.literal("Show peak tier"),
                (btn, value) -> {
                    cfg.showPeak = value;
                    try { cfg.save(); } catch (Throwable ignored) {}
                    try { TierTaggerCore.cache().invalidate(); } catch (Throwable ignored) {}
                }));

        y += rowH;
        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.fallthroughToHighest)
            .create(cx - btnW / 2, y, btnW, 20,
                Component.literal("Fall-through to highest tier"),
                (btn, value) -> { cfg.fallthroughToHighest = value; try { cfg.save(); } catch (Throwable ignored) {} }));

        y += rowH;
        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.coloredBadges)
            .create(cx - btnW / 2, y, btnW, 20,
                Component.literal("Coloured badges"),
                (btn, value) -> { cfg.coloredBadges = value; try { cfg.save(); } catch (Throwable ignored) {} }));

        y += rowH + 8;
        this.addRenderableWidget(Button.builder(Component.literal("Refresh cache"),
                btn -> { try { TierTaggerCore.cache().invalidate(); } catch (Throwable ignored) {} })
            .bounds(cx - btnW / 2, y, btnW, 20).build());

        y += rowH + 16;
        this.addRenderableWidget(Button.builder(Component.literal("Done"),
                btn -> closeSafely())
            .bounds(cx - 100, y, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        try {
            super.render(ctx, mouseX, mouseY, delta);
            ctx.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFF);
            TierConfig cfg = TierTaggerCore.config();
            String api = (cfg == null || cfg.apiBase == null) ? "?" : cfg.apiBase;
            ctx.drawCenteredString(this.font,
                Component.literal("API: " + api).withStyle(ChatFormatting.DARK_GRAY),
                this.width / 2, this.height - 18, 0x888888);
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] config screen render failed: {}", t.toString());
        }
    }

    private void closeSafely() {
        Minecraft mc = (this.minecraft != null) ? this.minecraft : Minecraft.getInstance();
        if (mc != null) mc.setScreen(parent);
    }

    @Override
    public void onClose() { closeSafely(); }
}

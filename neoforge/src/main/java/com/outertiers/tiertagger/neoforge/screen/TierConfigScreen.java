package com.outertiers.tiertagger.neoforge.screen;

import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierTaggerCore;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class TierConfigScreen extends Screen {
    private final Screen parent;

    public TierConfigScreen(Screen parent) {
        super(Component.literal("TierTagger – Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        TierConfig cfg = TierTaggerCore.config();
        int cx = this.width / 2;
        int y  = this.height / 6;
        int rowH = 24;
        int btnW = 220;

        this.addRenderableWidget(CycleButton.<String>builder(g -> Component.literal(g))
            .withValues(TierConfig.GAMEMODES)
            .withInitialValue(cfg.gamemode == null ? "overall" : cfg.gamemode)
            .create(cx - btnW / 2, y, btnW, 20,
                Component.literal("Gamemode"),
                (btn, value) -> {
                    cfg.gamemode = value;
                    cfg.save();
                    TierTaggerCore.cache().invalidate();
                }));

        y += rowH;
        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.showInTab)
            .create(cx - btnW / 2, y, btnW, 20,
                Component.literal("Tab list badges"),
                (btn, value) -> { cfg.showInTab = value; cfg.save(); }));

        y += rowH;
        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.showNametag)
            .create(cx - btnW / 2, y, btnW, 20,
                Component.literal("Nametag badges (above head)"),
                (btn, value) -> { cfg.showNametag = value; cfg.save(); }));

        y += rowH;
        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.showPeak)
            .create(cx - btnW / 2, y, btnW, 20,
                Component.literal("Show peak tier"),
                (btn, value) -> { cfg.showPeak = value; cfg.save(); TierTaggerCore.cache().invalidate(); }));

        y += rowH;
        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.fallthroughToHighest)
            .create(cx - btnW / 2, y, btnW, 20,
                Component.literal("Fall-through to highest tier"),
                (btn, value) -> { cfg.fallthroughToHighest = value; cfg.save(); }));

        y += rowH + 8;
        this.addRenderableWidget(Button.builder(Component.literal("Refresh cache"),
                btn -> TierTaggerCore.cache().invalidate())
            .bounds(cx - btnW / 2, y, btnW, 20).build());

        y += rowH + 16;
        this.addRenderableWidget(Button.builder(Component.literal("Done"),
                btn -> this.minecraft.setScreen(parent))
            .bounds(cx - 100, y, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFF);
        TierConfig cfg = TierTaggerCore.config();
        ctx.drawCenteredString(this.font,
            Component.literal("API: " + (cfg == null ? "?" : cfg.apiBase)),
            this.width / 2, this.height - 18, 0x888888);
    }

    @Override
    public void onClose() { this.minecraft.setScreen(parent); }
}

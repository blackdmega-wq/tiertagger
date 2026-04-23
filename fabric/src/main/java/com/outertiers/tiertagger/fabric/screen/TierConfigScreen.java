package com.outertiers.tiertagger.fabric.screen;

import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierTaggerCore;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.text.Text;

/**
 * Lightweight settings screen — no third-party UI dependency.
 * Open with {@code /tiertagger config}.
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
        int cx = this.width / 2;
        int y  = this.height / 6;
        int rowH = 24;
        int btnW = 220;

        // Gamemode cycler
        this.addDrawableChild(CyclingButtonWidget.<String>builder(g -> Text.literal(g))
            .values(TierConfig.GAMEMODES)
            .initially(cfg.gamemode == null ? "overall" : cfg.gamemode)
            .build(cx - btnW / 2, y, btnW, 20,
                Text.literal("Gamemode"),
                (btn, value) -> {
                    cfg.gamemode = value;
                    cfg.save();
                    TierTaggerCore.cache().invalidate();
                }));

        y += rowH;
        this.addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.showInTab)
            .build(cx - btnW / 2, y, btnW, 20,
                Text.literal("Tab list badges"),
                (btn, value) -> { cfg.showInTab = value; cfg.save(); }));

        y += rowH;
        this.addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.showNametag)
            .build(cx - btnW / 2, y, btnW, 20,
                Text.literal("Nametag badges (above head)"),
                (btn, value) -> { cfg.showNametag = value; cfg.save(); }));

        y += rowH;
        this.addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.showPeak)
            .build(cx - btnW / 2, y, btnW, 20,
                Text.literal("Show peak tier"),
                (btn, value) -> { cfg.showPeak = value; cfg.save(); TierTaggerCore.cache().invalidate(); }));

        y += rowH;
        this.addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.fallthroughToHighest)
            .build(cx - btnW / 2, y, btnW, 20,
                Text.literal("Fall-through to highest tier"),
                (btn, value) -> { cfg.fallthroughToHighest = value; cfg.save(); }));

        y += rowH + 8;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Refresh cache"),
                btn -> TierTaggerCore.cache().invalidate())
            .dimensions(cx - btnW / 2, y, btnW, 20).build());

        y += rowH + 16;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"),
                btn -> this.client.setScreen(parent))
            .dimensions(cx - 100, y, 200, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        this.renderBackground(ctx, mouseX, mouseY, delta);
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 16, 0xFFFFFF);
        TierConfig cfg = TierTaggerCore.config();
        ctx.drawCenteredTextWithShadow(this.textRenderer,
            Text.literal("API: " + (cfg == null ? "?" : cfg.apiBase)),
            this.width / 2, this.height - 18, 0x888888);
    }

    @Override
    public void close() { this.client.setScreen(parent); }
}

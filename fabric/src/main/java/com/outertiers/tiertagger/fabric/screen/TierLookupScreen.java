package com.outertiers.tiertagger.fabric.screen;

import com.outertiers.tiertagger.common.PlayerData;
import com.outertiers.tiertagger.common.Ranking;
import com.outertiers.tiertagger.common.ServiceData;
import com.outertiers.tiertagger.common.TierService;
import com.outertiers.tiertagger.common.TierTaggerCore;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Optional;

/**
 * GUI for /tiertagger lookup <player> — shows all four services' tiers in a
 * clean scrollable table.
 *
 * Layout:
 *   ──────────────────────────────────────────
 *   TierTagger – Lookup: Steve
 *   ──────────────────────────────────────────
 *   MCTiers          (NA)   #42
 *     vanilla        HT2    peak HT1
 *     sword          LT3
 *   ──────────────────────────────────────────
 *   OuterTiers       (EU)
 *     ogvanilla      HT3
 *   ...
 *   ──────────────────────────────────────────
 *   [Update]                          [Close]
 */
public class TierLookupScreen extends Screen {

    private final Screen parent;
    private final String username;
    private boolean bgApplied = false;

    private int scrollY   = 0;
    private int maxScroll = 0;

    private static final int PAD = 12;

    public TierLookupScreen(Screen parent, String username) {
        super(Text.literal("TierTagger – Lookup: " + (username == null ? "?" : username)));
        this.parent   = parent;
        this.username = username == null ? "" : username;
    }

    @Override
    protected void init() {
        try { TierTaggerCore.cache().peekData(username); } catch (Throwable ignored) {}
        scrollY = 0;

        int btnY = this.height - 25;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Update"), btn -> {
            TierTaggerCore.cache().invalidatePlayer(username);
            try { TierTaggerCore.cache().peekData(username); } catch (Throwable ignored) {}
        }).dimensions(PAD, btnY, 80, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"), btn -> closeSafely())
            .dimensions(this.width - PAD - 80, btnY, 80, 20).build());
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hd, double vd) {
        scrollY = Math.max(0, Math.min(maxScroll, scrollY - (int)(vd * 12)));
        return true;
    }

    // ── Blur-safe guard ──────────────────────────────────────────────────────
    @Override
    protected void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (bgApplied) return;
        bgApplied = true;
        super.renderBackground(ctx, mouseX, mouseY, delta);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        bgApplied = false;
        try {
            this.renderBackground(ctx, mouseX, mouseY, delta);

            // Title
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Lookup: ").formatted(Formatting.GRAY)
                    .append(Text.literal(username).formatted(Formatting.AQUA, Formatting.BOLD)),
                this.width / 2, 8, 0xFFFFFF);

            // Content
            int contentTop    = 24;
            int contentBottom = this.height - 30;
            ctx.enableScissor(0, contentTop, this.width, contentBottom);
            int y = drawContent(ctx, contentTop - scrollY);
            ctx.disableScissor();
            maxScroll = Math.max(0, y + scrollY - contentBottom);

            super.render(ctx, mouseX, mouseY, delta);
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] lookup render: {}", t.toString());
        }
    }

    private int drawContent(DrawContext ctx, int startY) {
        int x = PAD;
        int y = startY;
        int w = this.width - PAD * 2;

        Optional<PlayerData> opt = TierTaggerCore.cache().peekData(username);
        PlayerData data = opt.orElse(null);

        if (data == null) {
            ctx.drawTextWithShadow(this.textRenderer,
                Text.literal("Loading…").formatted(Formatting.DARK_GRAY), x, y + 4, 0x888888);
            return y + 20;
        }

        for (TierService svc : TierService.values()) {
            ServiceData sd = data.get(svc);

            // Service header bar
            ctx.fill(x, y, x + w, y + 1, svc.accentArgb);
            y += 3;

            // Service name + region + overall
            MutableText header = Text.literal(svc.displayName)
                .withColor(svc.accentArgb).formatted(Formatting.BOLD);

            if (sd != null && !sd.missing && sd.fetchedAt > 0) {
                if (sd.region != null && !sd.region.isBlank()) {
                    header = header.append(Text.literal("  (" + sd.region + ")")
                        .formatted(Formatting.AQUA));
                }
                if (sd.overall > 0) {
                    header = header.append(Text.literal("  #" + sd.overall)
                        .formatted(Formatting.GOLD));
                }
            }
            ctx.drawTextWithShadow(this.textRenderer, header, x, y, 0xFFFFFF);
            y += 13;

            if (sd == null || sd.fetchedAt == 0L) {
                ctx.drawTextWithShadow(this.textRenderer,
                    Text.literal("  Loading…").formatted(Formatting.DARK_GRAY), x, y, 0x888888);
                y += 12;
            } else if (sd.missing) {
                ctx.drawTextWithShadow(this.textRenderer,
                    Text.literal("  Not ranked on " + svc.displayName).formatted(Formatting.DARK_GRAY),
                    x, y, 0x666666);
                y += 12;
            } else {
                // Ranked — show modes
                boolean any = false;
                for (String mode : svc.modes) {
                    Ranking r = sd.rankings.get(mode);
                    if (r == null || r.tierLevel <= 0) continue;

                    String modeLabel = mode.replace("_", " ");
                    String curLabel  = r.label();
                    int    curColor  = TierTaggerCore.argbFor(curLabel);

                    MutableText row = Text.literal("  " + modeLabel)
                        .formatted(Formatting.GRAY);

                    // right-align tier within column
                    int rightX = x + w - 10;
                    MutableText tierTxt = Text.literal(curLabel).withColor(curColor).formatted(Formatting.BOLD);
                    if (r.peakDiffers()) {
                        String pk = r.peakLabel();
                        tierTxt = tierTxt.append(Text.literal(" (" + pk + ")")
                            .withColor(TierTaggerCore.argbFor(pk)));
                    }
                    ctx.drawTextWithShadow(this.textRenderer, row, x, y, 0xCCCCCC);
                    int tw = this.textRenderer.getWidth(tierTxt);
                    ctx.drawTextWithShadow(this.textRenderer, tierTxt, rightX - tw, y, curColor);
                    y += 12;
                    any = true;
                }
                if (!any) {
                    ctx.drawTextWithShadow(this.textRenderer,
                        Text.literal("  (no modes)").formatted(Formatting.DARK_GRAY), x, y, 0x666666);
                    y += 12;
                }
            }
            y += 4; // gap between services
        }
        return y;
    }

    private void closeSafely() {
        MinecraftClient mc = this.client != null ? this.client : MinecraftClient.getInstance();
        if (mc != null) mc.setScreen(parent);
    }

    @Override
    public void close() { closeSafely(); }
}

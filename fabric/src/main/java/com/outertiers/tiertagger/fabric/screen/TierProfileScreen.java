package com.outertiers.tiertagger.fabric.screen;

import com.outertiers.tiertagger.common.TierCache;
import com.outertiers.tiertagger.common.TierConfig;
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
 * Displays a player's tiers across all gamemodes, with OuterTiers-style colour swatches.
 * Defensive: every render path is wrapped so a malformed cache entry can never crash the
 * client.
 */
public class TierProfileScreen extends Screen {
    private final Screen parent;
    private final String username;

    public TierProfileScreen(Screen parent, String username) {
        super(Text.literal("TierTagger – " + (username == null ? "?" : username)));
        this.parent   = parent;
        this.username = username == null ? "" : username;
    }

    @Override
    protected void init() {
        try {
            TierTaggerCore.cache().peek(username);
        } catch (Throwable ignored) { /* fetch failures are logged elsewhere */ }

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"),
                btn -> {
                    try {
                        TierTaggerCore.cache().invalidatePlayer(username);
                        TierTaggerCore.cache().peek(username);
                    } catch (Throwable ignored) {}
                })
            .dimensions(this.width / 2 - 110, this.height - 28, 100, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"),
                btn -> closeSafely())
            .dimensions(this.width / 2 + 10, this.height - 28, 100, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        try {
            this.renderBackground(ctx, mouseX, mouseY, delta);
            super.render(ctx, mouseX, mouseY, delta);

            ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 14, 0xFFFFFF);

            Optional<TierCache.Entry> opt = TierTaggerCore.cache().peek(username);
            int cx = this.width / 2;
            int y  = 40;

            if (opt.isEmpty()) {
                ctx.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("Loading…").formatted(Formatting.GRAY), cx, y, 0xAAAAAA);
                return;
            }

            TierCache.Entry e = opt.get();
            if (e.missing) {
                ctx.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("No data found for " + username).formatted(Formatting.RED),
                    cx, y, 0xFF5555);
                return;
            }

            if (e.peakTier != null && !e.peakTier.isBlank() && !"-".equals(e.peakTier)) {
                Formatting pc = safeFormatting(TierTaggerCore.colourCodeFor(e.peakTier));
                MutableText peakText = Text.literal("Peak: ").formatted(Formatting.WHITE)
                    .append(Text.literal(e.peakTier.toUpperCase()).formatted(pc, Formatting.BOLD));
                ctx.drawCenteredTextWithShadow(this.textRenderer, peakText, cx, y, 0xFFFFFF);
                y += 14;
            }
            if (e.region != null && !e.region.isBlank() && !"-".equals(e.region)) {
                ctx.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("Region: " + e.region).formatted(Formatting.AQUA),
                    cx, y, 0x55FFFF);
                y += 14;
            }
            y += 6;

            // Two-column grid of gamemode rows
            int rowH    = 16;
            int colW    = 170;
            int leftX   = cx - colW;
            int rightX  = cx + 6;
            int i = 0;
            for (String mode : TierConfig.GAMEMODES) {
                if ("overall".equals(mode)) continue;
                String tier = (e.tiers == null) ? null : e.tiers.get(mode);
                int x = (i % 2 == 0) ? leftX : rightX;
                int rowY = y + (i / 2) * rowH;

                int colour = (tier == null) ? 0xFF333333 : TierTaggerCore.argbFor(tier);
                ctx.fill(x, rowY + 2, x + 10, rowY + 12, colour);
                ctx.drawBorder(x, rowY + 2, 10, 10, 0xFF000000);

                ctx.drawTextWithShadow(this.textRenderer,
                    Text.literal(pad(mode, 10)).formatted(Formatting.GRAY),
                    x + 16, rowY + 3, 0xCCCCCC);

                Text value;
                if (tier == null) {
                    value = Text.literal("unranked").formatted(Formatting.DARK_GRAY);
                } else {
                    Formatting tc = safeFormatting(TierTaggerCore.colourCodeFor(tier));
                    value = Text.literal(tier.toUpperCase()).formatted(tc, Formatting.BOLD);
                }
                ctx.drawTextWithShadow(this.textRenderer, value, x + 100, rowY + 3, 0xFFFFFF);

                i++;
            }
        } catch (Throwable t) {
            // Last-ditch: never let the screen crash the game.
            TierTaggerCore.LOGGER.warn("[TierTagger] profile screen render failed: {}", t.toString());
            try {
                ctx.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("Error rendering profile — see log").formatted(Formatting.RED),
                    this.width / 2, 40, 0xFF5555);
            } catch (Throwable ignored) {}
        }
    }

    private static Formatting safeFormatting(char code) {
        Formatting f = Formatting.byCode(code);
        return f == null ? Formatting.GRAY : f;
    }

    private static String pad(String s, int n) {
        if (s == null) return "";
        if (s.length() >= n) return s;
        StringBuilder b = new StringBuilder(s);
        while (b.length() < n) b.append(' ');
        return b.toString();
    }

    private void closeSafely() {
        MinecraftClient mc = (this.client != null) ? this.client : MinecraftClient.getInstance();
        if (mc != null) mc.setScreen(parent);
    }

    @Override
    public void close() { closeSafely(); }
}

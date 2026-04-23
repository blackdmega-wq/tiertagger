package com.outertiers.tiertagger.fabric.screen;

import com.outertiers.tiertagger.common.TierCache;
import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierTaggerCore;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Optional;

/**
 * Displays a player's tiers across all gamemodes, with OuterTiers-style colour swatches.
 * No third-party UI library required.
 */
public class TierProfileScreen extends Screen {
    private final Screen parent;
    private final String username;

    public TierProfileScreen(Screen parent, String username) {
        super(Text.literal("TierTagger – " + username));
        this.parent   = parent;
        this.username = username;
    }

    @Override
    protected void init() {
        // Trigger a fetch if we don't have data yet.
        TierTaggerCore.cache().peek(username);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"),
                btn -> { TierTaggerCore.cache().invalidate(); TierTaggerCore.cache().peek(username); })
            .dimensions(this.width / 2 - 110, this.height - 28, 100, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"),
                btn -> this.client.setScreen(parent))
            .dimensions(this.width / 2 + 10, this.height - 28, 100, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
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

        if (e.peakTier != null && !e.peakTier.isBlank()) {
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Peak: ").append(Text.literal(e.peakTier.toUpperCase())
                    .formatted(Formatting.byCode(TierTaggerCore.colourCodeFor(e.peakTier)), Formatting.BOLD)),
                cx, y, 0xFFFFFF);
            y += 14;
        }
        if (e.region != null && !e.region.isBlank()) {
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
            String tier = e.tiers == null ? null : e.tiers.get(mode);
            int x = (i % 2 == 0) ? leftX : rightX;
            int rowY = y + (i / 2) * rowH;

            // colour swatch
            int colour = tier == null ? 0xFF333333 : TierTaggerCore.argbFor(tier);
            ctx.fill(x, rowY + 2, x + 10, rowY + 12, colour);
            ctx.drawBorder(x, rowY + 2, 10, 10, 0xFF000000);

            // label
            ctx.drawTextWithShadow(this.textRenderer,
                Text.literal(pad(mode, 10)).formatted(Formatting.GRAY),
                x + 16, rowY + 3, 0xCCCCCC);

            // value
            Text value = tier == null
                ? Text.literal("unranked").formatted(Formatting.DARK_GRAY)
                : Text.literal(tier.toUpperCase()).formatted(
                    Formatting.byCode(TierTaggerCore.colourCodeFor(tier)), Formatting.BOLD);
            ctx.drawTextWithShadow(this.textRenderer, value, x + 100, rowY + 3, 0xFFFFFF);

            i++;
        }
    }

    private static String pad(String s, int n) {
        if (s.length() >= n) return s;
        StringBuilder b = new StringBuilder(s);
        while (b.length() < n) b.append(' ');
        return b.toString();
    }

    @Override
    public void close() { this.client.setScreen(parent); }
}

package com.outertiers.tiertagger.neoforge.screen;

import com.outertiers.tiertagger.common.TierCache;
import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierTaggerCore;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Optional;

public class TierProfileScreen extends Screen {
    private final Screen parent;
    private final String username;

    public TierProfileScreen(Screen parent, String username) {
        super(Component.literal("TierTagger – " + username));
        this.parent   = parent;
        this.username = username;
    }

    @Override
    protected void init() {
        TierTaggerCore.cache().peek(username);

        this.addRenderableWidget(Button.builder(Component.literal("Refresh"),
                btn -> { TierTaggerCore.cache().invalidate(); TierTaggerCore.cache().peek(username); })
            .bounds(this.width / 2 - 110, this.height - 28, 100, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Close"),
                btn -> this.minecraft.setScreen(parent))
            .bounds(this.width / 2 + 10, this.height - 28, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFF);

        Optional<TierCache.Entry> opt = TierTaggerCore.cache().peek(username);
        int cx = this.width / 2;
        int y  = 40;

        if (opt.isEmpty()) {
            ctx.drawCenteredString(this.font,
                Component.literal("Loading…").withStyle(ChatFormatting.GRAY), cx, y, 0xAAAAAA);
            return;
        }

        TierCache.Entry e = opt.get();
        if (e.missing) {
            ctx.drawCenteredString(this.font,
                Component.literal("No data found for " + username).withStyle(ChatFormatting.RED),
                cx, y, 0xFF5555);
            return;
        }

        if (e.peakTier != null && !e.peakTier.isBlank()) {
            ChatFormatting pc = ChatFormatting.getByCode(TierTaggerCore.colourCodeFor(e.peakTier));
            if (pc == null) pc = ChatFormatting.GRAY;
            ctx.drawCenteredString(this.font,
                Component.literal("Peak: ").append(Component.literal(e.peakTier.toUpperCase())
                    .withStyle(pc, ChatFormatting.BOLD)),
                cx, y, 0xFFFFFF);
            y += 14;
        }
        if (e.region != null && !e.region.isBlank()) {
            ctx.drawCenteredString(this.font,
                Component.literal("Region: " + e.region).withStyle(ChatFormatting.AQUA),
                cx, y, 0x55FFFF);
            y += 14;
        }
        y += 6;

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

            int colour = tier == null ? 0xFF333333 : TierTaggerCore.argbFor(tier);
            ctx.fill(x, rowY + 2, x + 10, rowY + 12, colour);
            ctx.renderOutline(x, rowY + 2, 10, 10, 0xFF000000);

            ctx.drawString(this.font, pad(mode, 10), x + 16, rowY + 3, 0xCCCCCC);

            Component value;
            if (tier == null) {
                value = Component.literal("unranked").withStyle(ChatFormatting.DARK_GRAY);
            } else {
                ChatFormatting tc = ChatFormatting.getByCode(TierTaggerCore.colourCodeFor(tier));
                if (tc == null) tc = ChatFormatting.GRAY;
                value = Component.literal(tier.toUpperCase()).withStyle(tc, ChatFormatting.BOLD);
            }
            ctx.drawString(this.font, value, x + 100, rowY + 3, 0xFFFFFF);

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
    public void onClose() { this.minecraft.setScreen(parent); }
}

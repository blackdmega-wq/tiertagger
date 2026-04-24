package com.outertiers.tiertagger.neoforge.screen;

import com.outertiers.tiertagger.common.PlayerData;
import com.outertiers.tiertagger.common.Ranking;
import com.outertiers.tiertagger.common.ServiceData;
import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierService;
import com.outertiers.tiertagger.common.TierTaggerCore;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Optional;

/**
 * Split-screen comparison screen (NeoForge).
 * Shows two players' tiers side by side, colour-coded by winner, with win summary.
 */
public class TierCompareScreen extends Screen {

    private final Screen parent;
    private final String nameA;
    private final String nameB;

    private int scrollY   = 0;
    private int maxScroll = 0;

    public TierCompareScreen(Screen parent, String nameA, String nameB) {
        super(Component.literal(nameA + " vs " + nameB));
        this.parent = parent;
        this.nameA  = nameA == null ? "" : nameA;
        this.nameB  = nameB == null ? "" : nameB;
    }

    @Override
    protected void init() {
        try { TierTaggerCore.cache().peekData(nameA); } catch (Throwable ignored) {}
        try { TierTaggerCore.cache().peekData(nameB); } catch (Throwable ignored) {}
        scrollY = 0;

        int btnY = this.height - 24;
        this.addRenderableWidget(Button.builder(Component.literal("↺ " + nameA), btn -> {
            TierTaggerCore.cache().invalidatePlayer(nameA);
            try { TierTaggerCore.cache().peekData(nameA); } catch (Throwable ignored) {}
        }).bounds(8, btnY, 80, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Close"), btn -> closeSafely())
            .bounds(this.width / 2 - 40, btnY, 80, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("↺ " + nameB), btn -> {
            TierTaggerCore.cache().invalidatePlayer(nameB);
            try { TierTaggerCore.cache().peekData(nameB); } catch (Throwable ignored) {}
        }).bounds(this.width - 88, btnY, 80, 20).build());
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hDelta, double vDelta) {
        scrollY = Math.max(0, Math.min(maxScroll, scrollY - (int)(vDelta * 12)));
        return true;
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        try {
            super.renderBackground(ctx, mouseX, mouseY, delta);

            Optional<PlayerData> optA = TierTaggerCore.cache().peekData(nameA);
            Optional<PlayerData> optB = TierTaggerCore.cache().peekData(nameB);
            PlayerData dA = optA.orElse(null);
            PlayerData dB = optB.orElse(null);

            int cx = this.width / 2;
            ctx.drawCenteredString(this.font,
                Component.literal("§b§l" + nameA + " §r§7vs §b§l" + nameB),
                cx, 10, 0xFFFFFF);

            renderHeader(ctx, dA, dB);

            int contentTop    = 76;
            int contentBottom = this.height - 30;
            ctx.enableScissor(0, contentTop, this.width, contentBottom);
            int drawY = contentTop - scrollY;
            drawY = renderBody(ctx, dA, dB, drawY);
            ctx.disableScissor();
            maxScroll = Math.max(0, drawY + scrollY - contentBottom);

            super.render(ctx, mouseX, mouseY, delta);
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] compare screen error: {}", t.toString());
        }
    }

    private void renderHeader(GuiGraphics ctx, PlayerData dA, PlayerData dB) {
        int cx = this.width / 2;
        ctx.drawCenteredString(this.font,
            Component.literal(nameA).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
            cx / 2, 30, 0xFFFFFF);
        ctx.drawCenteredString(this.font,
            Component.literal("vs").withStyle(ChatFormatting.DARK_GRAY),
            cx, 30, 0xAAAAAA);
        ctx.drawCenteredString(this.font,
            Component.literal(nameB).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
            cx + cx / 2, 30, 0xFFFFFF);

        // Highest tier pills
        TierConfig cfg = TierTaggerCore.config();
        if (cfg != null && dA != null && dB != null) {
            TierService ls = cfg.leftServiceEnum();
            Ranking rA = dA.get(ls).highest();
            Ranking rB = dB.get(ls).highest();
            if (rA != null) drawBadgePill(ctx, rA.label(), cx / 2, 44);
            if (rB != null) drawBadgePill(ctx, rB.label(), cx + cx / 2, 44);
        }

        // Separator
        ctx.fill(16, 60, this.width - 16, 61, 0xFF444444);
    }

    private void drawBadgePill(GuiGraphics ctx, String tier, int cx, int y) {
        int color = TierTaggerCore.argbFor(tier);
        String txt = "[" + tier + "]";
        int w = this.font.width(txt) + 8;
        int bx = cx - w / 2;
        ctx.fill(bx - 1, y - 1, bx + w + 1, y + 11, 0xCC000000);
        ctx.drawCenteredString(this.font, Component.literal(txt).withColor(color), cx, y + 1, color);
    }

    private int renderBody(GuiGraphics ctx, PlayerData dA, PlayerData dB, int y) {
        int cx = this.width / 2;
        int wins1 = 0, wins2 = 0, ties = 0;

        for (TierService svc : TierService.values()) {
            y += 4;
            ctx.fill(0, y, this.width, y + 14, 0xFF1C2028);
            ctx.fill(0, y, 3, y + 14, svc.accentArgb);
            ctx.drawString(this.font, Component.literal(svc.shortLabel).withColor(svc.accentArgb).withStyle(ChatFormatting.BOLD), 8, y + 3, svc.accentArgb);
            ctx.drawString(this.font, Component.literal(svc.displayName).withStyle(ChatFormatting.WHITE),
                8 + this.font.width(svc.shortLabel) + 4, y + 3, 0xFFFFFF);
            y += 14;

            ctx.drawCenteredString(this.font, Component.literal(nameA).withStyle(ChatFormatting.YELLOW), cx / 2, y, 0xFFAA00);
            ctx.drawCenteredString(this.font, Component.literal(nameB).withStyle(ChatFormatting.YELLOW), cx + cx / 2, y, 0xFFAA00);
            y += 10;

            ServiceData sdA = dA == null ? null : dA.get(svc);
            ServiceData sdB = dB == null ? null : dB.get(svc);
            boolean anyDrawn = false;

            for (String mode : svc.modes) {
                Ranking rA = sdA == null ? null : sdA.rankings.get(mode);
                Ranking rB = sdB == null ? null : sdB.rankings.get(mode);
                boolean hasA = rA != null && rA.tierLevel > 0;
                boolean hasB = rB != null && rB.tierLevel > 0;
                if (!hasA && !hasB) continue;

                ctx.drawCenteredString(this.font, Component.literal(mode).withStyle(ChatFormatting.GRAY), cx, y + 1, 0x888888);

                Component tA = tierComp(rA, sdA);
                Component tB = tierComp(rB, sdB);
                int wA = this.font.width(tA);
                int wB = this.font.width(tB);
                ctx.drawString(this.font, tA, cx / 2 - wA / 2, y + 1, 0xFFFFFF);
                ctx.drawString(this.font, tB, cx + cx / 2 - wB / 2, y + 1, 0xFFFFFF);

                if (hasA || hasB) {
                    int sA = hasA ? rA.score() : -1;
                    int sB = hasB ? rB.score() : -1;
                    MutableComponent marker;
                    if (sA > sB)      { marker = Component.literal("◀").withStyle(ChatFormatting.GREEN); wins1++; }
                    else if (sB > sA) { marker = Component.literal("▶").withStyle(ChatFormatting.GREEN); wins2++; }
                    else              { marker = Component.literal("=").withStyle(ChatFormatting.YELLOW); ties++; }
                    ctx.drawCenteredString(this.font, marker, cx, y + 1, 0xFFFFFF);
                }
                y += 12;
                anyDrawn = true;
            }

            if (!anyDrawn) {
                ctx.drawCenteredString(this.font,
                    Component.literal(sdA != null && sdA.fetchedAt == 0 ? "Loading…" : "No data")
                        .withStyle(ChatFormatting.DARK_GRAY),
                    cx, y + 1, 0x666666);
                y += 12;
            }
        }

        y += 6;
        ctx.fill(16, y, this.width - 16, y + 1, 0xFF444444);
        y += 4;
        String summary = "Wins:  " + nameA + ": " + wins1 + "   " + nameB + ": " + wins2 + "   ties: " + ties;
        ctx.drawCenteredString(this.font, Component.literal(summary).withStyle(ChatFormatting.GRAY), cx, y + 1, 0xAAAAAA);
        return y + 14;
    }

    private Component tierComp(Ranking r, ServiceData sd) {
        if (sd != null && sd.fetchedAt == 0L) return Component.literal("…").withStyle(ChatFormatting.DARK_GRAY);
        if (r == null || r.tierLevel <= 0)    return Component.literal("—").withStyle(ChatFormatting.DARK_GRAY);
        String lbl = r.label();
        return Component.literal(lbl).withColor(TierTaggerCore.argbFor(lbl)).copy().withStyle(ChatFormatting.BOLD);
    }

    private void closeSafely() {
        Minecraft mc = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        if (mc != null) mc.setScreen(parent);
    }

    @Override
    public void onClose() { closeSafely(); }
}

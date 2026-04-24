package com.outertiers.tiertagger.fabric.screen;

import com.outertiers.tiertagger.common.PlayerData;
import com.outertiers.tiertagger.common.Ranking;
import com.outertiers.tiertagger.common.ServiceData;
import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierService;
import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.fabric.compat.Compat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.Optional;

/**
 * Split-screen comparison screen.
 *
 * Layout:
 *  ┌────────────────────────────────────────────────────────────┐
 *  │              Steve           vs          Notch             │
 *  │            [head]          vs icon       [head]            │
 *  │          Left-badge                   Right-badge          │
 *  │  ─────────────────────────────────────────────────────     │
 *  │  MCTiers                                                   │
 *  │    overall       T2          ◀         T3                  │
 *  │    vanilla       HT2         =         HT2                 │
 *  │  OuterTiers                                                │
 *  │    ...                                                     │
 *  │  ─────────────────────────────────────────────────────     │
 *  │  [Update A]   Wins: Steve 3  Notch 1  ties 2   [Update B] │
 *  │                       [Close]                              │
 *  └────────────────────────────────────────────────────────────┘
 */
public class TierCompareScreen extends Screen {

    private static final Identifier STEVE = Identifier.ofVanilla("textures/entity/player/wide/steve.png");

    private final Screen parent;
    private final String nameA;
    private final String nameB;

    private int scrollY = 0;
    private int maxScroll = 0;

    public TierCompareScreen(Screen parent, String nameA, String nameB) {
        super(Text.literal(nameA + " vs " + nameB));
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
        // Update A
        this.addDrawableChild(ButtonWidget.builder(Text.literal("↺ " + nameA), btn -> {
            TierTaggerCore.cache().invalidatePlayer(nameA);
            try { TierTaggerCore.cache().peekData(nameA); } catch (Throwable ignored) {}
        }).dimensions(8, btnY, 80, 20).build());

        // Close
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"), btn -> closeSafely())
            .dimensions(this.width / 2 - 40, btnY, 80, 20).build());

        // Update B
        this.addDrawableChild(ButtonWidget.builder(Text.literal("↺ " + nameB), btn -> {
            TierTaggerCore.cache().invalidatePlayer(nameB);
            try { TierTaggerCore.cache().peekData(nameB); } catch (Throwable ignored) {}
        }).dimensions(this.width - 88, btnY, 80, 20).build());
    }

    // ── scroll ──────────────────────────────────────────────────────────────

    @Override
    public boolean mouseScrolled(double mx, double my, double hDelta, double vDelta) {
        scrollY = Math.max(0, Math.min(maxScroll, scrollY - (int)(vDelta * 12)));
        return true;
    }

    // ── render ──────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        try {
            this.renderBackground(ctx, mouseX, mouseY, delta);

            Optional<PlayerData> optA = TierTaggerCore.cache().peekData(nameA);
            Optional<PlayerData> optB = TierTaggerCore.cache().peekData(nameB);

            PlayerData dA = optA.orElse(null);
            PlayerData dB = optB.orElse(null);

            // Title row
            int cx = this.width / 2;
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("§b§l" + nameA + " §r§7vs §b§l" + nameB),
                cx, 10, 0xFFFFFF);

            // Heads + badge pills
            renderHeader(ctx, dA, dB);

            // Content area with clip
            int contentTop = 80;
            int contentBottom = this.height - 30;
            ctx.enableScissor(0, contentTop, this.width, contentBottom);
            int drawY = contentTop - scrollY;
            drawY = renderBody(ctx, dA, dB, drawY, contentBottom);
            ctx.disableScissor();
            maxScroll = Math.max(0, drawY + scrollY - contentBottom);

            super.render(ctx, mouseX, mouseY, delta);
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] compare screen error: {}", t.toString());
        }
    }

    // ── header (two heads + badge pills) ────────────────────────────────────

    private void renderHeader(DrawContext ctx, PlayerData dA, PlayerData dB) {
        int headSize = 36;
        int cx = this.width / 2;

        // Left head
        int axHead = cx / 2 - headSize / 2;
        drawHead(ctx, nameA, dA, axHead, 30, headSize);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
            Text.literal(nameA).formatted(Formatting.AQUA, Formatting.BOLD),
            axHead + headSize / 2, 30 + headSize + 3, 0xFFFFFF);

        // "vs" in the center
        ctx.drawCenteredTextWithShadow(this.textRenderer,
            Text.literal("vs").formatted(Formatting.DARK_GRAY, Formatting.BOLD),
            cx, 44, 0xAAAAAA);

        // Right head
        int bxHead = cx + cx / 2 - headSize / 2;
        drawHead(ctx, nameB, dB, bxHead, 30, headSize);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
            Text.literal(nameB).formatted(Formatting.AQUA, Formatting.BOLD),
            bxHead + headSize / 2, 30 + headSize + 3, 0xFFFFFF);

        // Highest-tier badge pills
        TierConfig cfg = TierTaggerCore.config();
        if (cfg != null && dA != null && dB != null) {
            TierService ls = cfg.leftServiceEnum();
            Ranking rA = dA.get(ls).highest();
            Ranking rB = dB.get(ls).highest();
            int pillY = 65;
            if (rA != null) drawBadgePill(ctx, rA.label(), axHead + headSize / 2, pillY, true);
            if (rB != null) drawBadgePill(ctx, rB.label(), bxHead + headSize / 2, pillY, false);
        }

        // Separator
        ctx.fill(16, 76, this.width - 16, 77, 0xFF444444);
    }

    private void drawHead(DrawContext ctx, String name, PlayerData data, int x, int y, int size) {
        SkinTextures st = null;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.getNetworkHandler() != null) {
                PlayerListEntry e = mc.getNetworkHandler().getPlayerListEntry(name);
                if (e != null) st = e.getSkinTextures();
            }
        } catch (Throwable ignored) {}
        try {
            ctx.fill(x - 2, y - 2, x + size + 2, y + size + 2, 0xFF1A1A1A);
            Compat.drawPlayerFace(ctx, st, STEVE, x, y, size);
        } catch (Throwable t) {
            ctx.fill(x, y, x + size, y + size, 0xFF6E4A2A);
        }
    }

    private void drawBadgePill(DrawContext ctx, String tier, int cx, int y, boolean leftAligned) {
        int color = TierTaggerCore.argbFor(tier);
        String txt = "[" + tier + "]";
        int w = this.textRenderer.getWidth(txt) + 8;
        int bx = leftAligned ? cx - w / 2 : cx - w / 2;
        ctx.fill(bx - 1, y - 1, bx + w + 1, y + 11, 0xCC000000);
        ctx.fill(bx, y, bx + w, y + 10, color & 0x44FFFFFF | 0x88000000);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(txt).withColor(color), cx, y + 1, color);
    }

    // ── body (per-service comparison rows) ──────────────────────────────────

    private int renderBody(DrawContext ctx, PlayerData dA, PlayerData dB, int y, int bottomBound) {
        int cx = this.width / 2;
        int margin = 20;
        int colW = (this.width / 2 - margin - 20);

        int wins1 = 0, wins2 = 0, ties = 0;

        for (TierService svc : TierService.values()) {
            if (y > bottomBound + 200) break;

            // Service header band
            y += 4;
            ctx.fill(0, y, this.width, y + 14, 0xFF1C2028);
            ctx.fill(0, y, 3, y + 14, svc.accentArgb);
            ctx.drawTextWithShadow(this.textRenderer,
                Text.literal(svc.shortLabel).withColor(svc.accentArgb).copy().formatted(Formatting.BOLD),
                8, y + 3, svc.accentArgb);
            ctx.drawTextWithShadow(this.textRenderer,
                Text.literal(svc.displayName).formatted(Formatting.WHITE),
                8 + this.textRenderer.getWidth(svc.shortLabel) + 4, y + 3, 0xFFFFFF);
            // Region/status on right side
            if (dA != null && dB != null) {
                ServiceData sdA = dA.get(svc);
                ServiceData sdB = dB.get(svc);
                String regA = sdA.region == null ? "—" : sdA.region;
                String regB = sdB.region == null ? "—" : sdB.region;
                String info = regA + "  vs  " + regB;
                ctx.drawTextWithShadow(this.textRenderer,
                    Text.literal(info).formatted(Formatting.DARK_GRAY),
                    this.width - this.textRenderer.getWidth(info) - 8, y + 3, 0x777777);
            }
            y += 14;

            // Column headers once
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(nameA).formatted(Formatting.YELLOW), cx / 2, y, 0xFFAA00);
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(nameB).formatted(Formatting.YELLOW), cx + cx / 2, y, 0xFFAA00);
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

                int rowH = 12;
                int rowBg = anyDrawn && (svc.modes.indexOf(mode) % 2 == 0) ? 0x0A000000 : 0x00000000;
                if (rowBg != 0) ctx.fill(0, y, this.width, y + rowH, rowBg);

                // Mode label
                ctx.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal(mode).formatted(Formatting.GRAY), cx, y + 1, 0x888888);

                // Player A tier
                Text tA = tierText(rA, sdA);
                int wA = this.textRenderer.getWidth(tA);
                ctx.drawTextWithShadow(this.textRenderer, tA, cx / 2 - wA / 2, y + 1, 0xFFFFFF);

                // Player B tier
                Text tB = tierText(rB, sdB);
                int wB = this.textRenderer.getWidth(tB);
                ctx.drawTextWithShadow(this.textRenderer, tB, cx + cx / 2 - wB / 2, y + 1, 0xFFFFFF);

                // Win indicator
                if (hasA || hasB) {
                    int sA = hasA ? rA.score() : -1;
                    int sB = hasB ? rB.score() : -1;
                    MutableText marker;
                    if (sA > sB)      { marker = Text.literal("◀").formatted(Formatting.GREEN); wins1++; }
                    else if (sB > sA) { marker = Text.literal("▶").formatted(Formatting.GREEN); wins2++; }
                    else              { marker = Text.literal("=").formatted(Formatting.YELLOW); ties++; }
                    ctx.drawCenteredTextWithShadow(this.textRenderer, marker, cx, y + 1, 0xFFFFFF);
                }
                y += rowH;
                anyDrawn = true;
            }

            if (!anyDrawn) {
                ctx.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal(sdA != null && sdA.fetchedAt == 0 ? "Loading…" : "No data")
                        .formatted(Formatting.DARK_GRAY),
                    cx, y + 1, 0x666666);
                y += 12;
            }
        }

        // Summary
        y += 6;
        ctx.fill(16, y, this.width - 16, y + 1, 0xFF444444);
        y += 4;
        String summary = "Wins:  " + nameA + ": " + wins1 + "   " + nameB + ": " + wins2 + "   ties: " + ties;
        ctx.drawCenteredTextWithShadow(this.textRenderer,
            Text.literal(summary).formatted(Formatting.GRAY), cx, y + 1, 0xAAAAAA);
        y += 14;

        return y;
    }

    private Text tierText(Ranking r, ServiceData sd) {
        if (sd != null && sd.fetchedAt == 0L) return Text.literal("…").formatted(Formatting.DARK_GRAY);
        if (r == null || r.tierLevel <= 0)    return Text.literal("—").formatted(Formatting.DARK_GRAY);
        String lbl = r.label();
        return Text.literal(lbl).withColor(TierTaggerCore.argbFor(lbl)).copy().formatted(Formatting.BOLD);
    }

    private void closeSafely() {
        MinecraftClient mc = this.client != null ? this.client : MinecraftClient.getInstance();
        if (mc != null) mc.setScreen(parent);
    }

    @Override
    public void close() { closeSafely(); }
}

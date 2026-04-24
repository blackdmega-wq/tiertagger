package com.outertiers.tiertagger.fabric.screen;

import com.outertiers.tiertagger.common.PlayerData;
import com.outertiers.tiertagger.common.Ranking;
import com.outertiers.tiertagger.common.ServiceData;
import com.outertiers.tiertagger.common.TierIcons;
import com.outertiers.tiertagger.common.TierService;
import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.fabric.compat.Compat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Side-by-side comparison: each tier-list is its own card, and within the
 * card every mode shows BOTH players' tiers next to each other so you can
 * read across the row "vanilla — A:HT2 vs B:HT3" without scanning.
 *
 *   ┌─────────────── opaque panel ──────────────┐
 *   │   [head]  PlayerA          [head]  PlayerB │
 *   │   ──────────────────────────────────────── │
 *   │   ▍ MCTiers                                │
 *   │     🗡 Vanilla   HT2   ◀     HT3           │
 *   │     ⚒ Sword     LT3   =     LT3           │
 *   │   ▍ OuterTiers                             │
 *   │     ...                                    │
 *   │   ──────────────────────────────────────── │
 *   │   Wins: A 4   B 6   ties 2                 │
 *   │   [↻ A]                            [↻ B]   │
 *   └────────────────────────────────────────────┘
 */
public class TierCompareScreen extends Screen {

    private static final Identifier STEVE = Identifier.ofVanilla("textures/entity/player/wide/steve.png");

    private static final int PANEL_W_MAX = 560;
    private static final int CARD_GAP    = 6;
    private static final int CARD_PAD    = 8;
    private static final int ROW_H       = 14;

    private static final int BG_PANEL    = 0xF20E1116;
    private static final int BG_PANEL_BORDER = 0xFF2A2F38;
    private static final int BG_HEADER   = 0xFF181C24;
    private static final int BG_CARD     = 0xFF15191F;
    private static final int BG_CARD_BAR = 0xFF1E232C;
    private static final int FG_FAINT    = 0x9AA0AA;
    private static final int FG_TEXT     = 0xE6E8EC;

    private final Screen parent;
    private final String nameA;
    private final String nameB;
    private boolean bgApplied = false;
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

        int panelW = Math.min(PANEL_W_MAX, this.width - 40);
        int panelX = (this.width - panelW) / 2;
        int btnY   = this.height - 28;

        this.addDrawableChild(ButtonWidget.builder(Text.literal("\u21BB " + nameA), btn -> {
                try { TierTaggerCore.cache().invalidatePlayer(nameA);
                      TierTaggerCore.cache().peekData(nameA); } catch (Throwable ignored) {}
            })
            .dimensions(panelX + CARD_PAD, btnY, 90, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"), btn -> closeSafely())
            .dimensions(this.width / 2 - 40, btnY, 80, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("\u21BB " + nameB), btn -> {
                try { TierTaggerCore.cache().invalidatePlayer(nameB);
                      TierTaggerCore.cache().peekData(nameB); } catch (Throwable ignored) {}
            })
            .dimensions(panelX + panelW - CARD_PAD - 90, btnY, 90, 20).build());
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hd, double vd) {
        scrollY = Math.max(0, Math.min(maxScroll, scrollY - (int)(vd * 16)));
        return true;
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (bgApplied) return;
        bgApplied = true;
        try { super.renderBackground(ctx, mouseX, mouseY, delta); } catch (Throwable ignored) {}
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        bgApplied = false;
        try {
            this.renderBackground(ctx, mouseX, mouseY, delta);

            int panelW = Math.min(PANEL_W_MAX, this.width - 40);
            int panelX = (this.width - panelW) / 2;
            int panelTop = 18;
            int panelBottom = this.height - 36;

            fillRect(ctx, panelX, panelTop, panelX + panelW, panelBottom, BG_PANEL);
            outlineRect(ctx, panelX, panelTop, panelW, panelBottom - panelTop, BG_PANEL_BORDER);

            Optional<PlayerData> optA = TierTaggerCore.cache().peekData(nameA);
            Optional<PlayerData> optB = TierTaggerCore.cache().peekData(nameB);
            PlayerData dA = optA.orElse(null);
            PlayerData dB = optB.orElse(null);

            int headerH = 50;
            renderHeader(ctx, dA, dB, panelX + CARD_PAD, panelTop + CARD_PAD,
                         panelW - CARD_PAD * 2, headerH);

            int bodyTop    = panelTop + CARD_PAD + headerH + 6;
            int bodyBottom = panelBottom - CARD_PAD;
            ctx.enableScissor(panelX + 1, bodyTop, panelX + panelW - 1, bodyBottom);
            int y = bodyTop - scrollY;
            y = renderCards(ctx, dA, dB, panelX + CARD_PAD, y, panelW - CARD_PAD * 2);
            ctx.disableScissor();
            maxScroll = Math.max(0, y + scrollY - bodyBottom);

            super.render(ctx, mouseX, mouseY, delta);
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] compare render: {}", t.toString());
        }
    }

    // ── header ──────────────────────────────────────────────────────────────

    private void renderHeader(DrawContext ctx, PlayerData dA, PlayerData dB,
                              int x, int y, int w, int h) {
        fillRect(ctx, x, y, x + w, y + h, BG_HEADER);

        int headSize = h - 12;
        int leftHeadX  = x + 8;
        int rightHeadX = x + w - 8 - headSize;
        int headY      = y + (h - headSize) / 2;
        drawHead(ctx, nameA, leftHeadX,  headY, headSize);
        drawHead(ctx, nameB, rightHeadX, headY, headSize);

        // Names + highest tier under name
        ctx.drawTextWithShadow(this.textRenderer,
            Text.literal(nameA).formatted(Formatting.WHITE, Formatting.BOLD),
            leftHeadX + headSize + 8, y + 8, 0xFFFFFFFF);
        ctx.drawTextWithShadow(this.textRenderer,
            Text.literal(nameB).formatted(Formatting.WHITE, Formatting.BOLD),
            rightHeadX - 8 - this.textRenderer.getWidth(nameB), y + 8, 0xFFFFFFFF);

        Ranking bestA = highestOverall(dA);
        Ranking bestB = highestOverall(dB);
        if (bestA != null) {
            String s = bestA.label();
            int c = TierTaggerCore.argbFor(s) & 0xFFFFFF;
            ctx.drawTextWithShadow(this.textRenderer, Text.literal(s).withColor(c).copy().formatted(Formatting.BOLD),
                leftHeadX + headSize + 8, y + 22, c);
        } else if (dA == null) {
            ctx.drawTextWithShadow(this.textRenderer, Text.literal("loading…").withColor(FG_FAINT),
                leftHeadX + headSize + 8, y + 22, FG_FAINT);
        }
        if (bestB != null) {
            String s = bestB.label();
            int c = TierTaggerCore.argbFor(s) & 0xFFFFFF;
            int sw = this.textRenderer.getWidth(s);
            ctx.drawTextWithShadow(this.textRenderer, Text.literal(s).withColor(c).copy().formatted(Formatting.BOLD),
                rightHeadX - 8 - sw, y + 22, c);
        } else if (dB == null) {
            int sw = this.textRenderer.getWidth("loading…");
            ctx.drawTextWithShadow(this.textRenderer, Text.literal("loading…").withColor(FG_FAINT),
                rightHeadX - 8 - sw, y + 22, FG_FAINT);
        }

        // Centred "vs"
        ctx.drawCenteredTextWithShadow(this.textRenderer,
            Text.literal("vs").formatted(Formatting.GRAY, Formatting.BOLD),
            x + w / 2, y + h / 2 - 4, 0xCCCCCC);
    }

    private static Ranking highestOverall(PlayerData d) {
        if (d == null) return null;
        Ranking best = null;
        for (TierService s : TierService.values()) {
            Ranking r = d.get(s).highest();
            if (r == null) continue;
            if (best == null || r.score() > best.score()) best = r;
        }
        return best;
    }

    // ── cards ───────────────────────────────────────────────────────────────

    private int renderCards(DrawContext ctx, PlayerData dA, PlayerData dB, int x, int y, int w) {
        int wins1 = 0, wins2 = 0, ties = 0;

        for (TierService svc : TierService.values()) {
            ServiceData sdA = dA == null ? null : dA.get(svc);
            ServiceData sdB = dB == null ? null : dB.get(svc);

            // Union of modes from this service plus any modes either player actually has
            Set<String> allModes = new LinkedHashSet<>(svc.modes);
            if (sdA != null) allModes.addAll(sdA.rankings.keySet());
            if (sdB != null) allModes.addAll(sdB.rankings.keySet());

            // Only show modes where at least one player has data (keeps the card compact)
            int rowsToDraw = 0;
            for (String m : allModes) {
                Ranking rA = sdA == null ? null : sdA.rankings.get(m);
                Ranking rB = sdB == null ? null : sdB.rankings.get(m);
                if ((rA != null && rA.tierLevel > 0) || (rB != null && rB.tierLevel > 0)) rowsToDraw++;
            }
            int cardH = 22 + Math.max(1, rowsToDraw) * ROW_H + 6;

            // Card chrome
            fillRect(ctx, x, y, x + w, y + cardH, BG_CARD);
            outlineRect(ctx, x, y, w, cardH, 0xFF2A2F38);
            fillRect(ctx, x, y, x + 3, y + cardH, svc.accentArgb);
            fillRect(ctx, x + 3, y, x + w, y + 22, BG_CARD_BAR);

            // Header text
            ctx.drawTextWithShadow(this.textRenderer,
                Text.literal(svc.shortLabel).withColor(svc.accentArgb & 0xFFFFFF).copy().formatted(Formatting.BOLD),
                x + 10, y + 7, svc.accentArgb & 0xFFFFFF);
            ctx.drawTextWithShadow(this.textRenderer,
                Text.literal(svc.displayName).formatted(Formatting.WHITE),
                x + 10 + this.textRenderer.getWidth(svc.shortLabel) + 6, y + 7, FG_TEXT);

            // Right side: per-player region pill
            String regs = regionPair(sdA, sdB);
            int rsw = this.textRenderer.getWidth(regs);
            ctx.drawTextWithShadow(this.textRenderer, Text.literal(regs).withColor(FG_FAINT),
                x + w - 10 - rsw, y + 7, FG_FAINT);

            int rowY = y + 24;
            if (rowsToDraw == 0) {
                String msg = (sdA != null && sdA.fetchedAt == 0) || (sdB != null && sdB.fetchedAt == 0)
                    ? "loading…" : "neither player ranked here";
                ctx.drawTextWithShadow(this.textRenderer,
                    Text.literal(msg).formatted(Formatting.DARK_GRAY),
                    x + 10, rowY + 2, 0x808080);
            } else {
                boolean alt = false;
                for (String mode : allModes) {
                    Ranking rA = sdA == null ? null : sdA.rankings.get(mode);
                    Ranking rB = sdB == null ? null : sdB.rankings.get(mode);
                    boolean hasA = rA != null && rA.tierLevel > 0;
                    boolean hasB = rB != null && rB.tierLevel > 0;
                    if (!hasA && !hasB) continue;

                    int sA = hasA ? rA.score() : -1;
                    int sB = hasB ? rB.score() : -1;
                    char winner;
                    if (sA > sB)      { winner = 'A'; wins1++; }
                    else if (sB > sA) { winner = 'B'; wins2++; }
                    else              { winner = '='; ties++; }

                    renderCmpRow(ctx, mode, rA, rB, winner, x + 10, rowY, w - 20, alt);
                    rowY += ROW_H;
                    alt = !alt;
                }
            }
            y += cardH + CARD_GAP;
        }

        // Footer summary
        y += 4;
        fillRect(ctx, x, y, x + w, y + 22, BG_HEADER);
        outlineRect(ctx, x, y, w, 22, 0xFF2A2F38);
        MutableText sum = Text.literal("Wins  ").formatted(Formatting.GRAY)
            .append(Text.literal(nameA + ": ").formatted(Formatting.WHITE))
            .append(Text.literal(String.valueOf(wins1)).formatted(Formatting.GREEN, Formatting.BOLD))
            .append(Text.literal("    " + nameB + ": ").formatted(Formatting.WHITE))
            .append(Text.literal(String.valueOf(wins2)).formatted(Formatting.GREEN, Formatting.BOLD))
            .append(Text.literal("    ties: ").formatted(Formatting.WHITE))
            .append(Text.literal(String.valueOf(ties)).formatted(Formatting.YELLOW, Formatting.BOLD));
        ctx.drawCenteredTextWithShadow(this.textRenderer, sum, x + w / 2, y + 7, 0xFFFFFF);
        return y + 26;
    }

    private void renderCmpRow(DrawContext ctx, String mode, Ranking rA, Ranking rB,
                              char winner, int x, int y, int w, boolean alt) {
        if (alt) fillRect(ctx, x - 4, y, x + w + 4, y + ROW_H, 0x14FFFFFF);

        int textX = x;
        try {
            Identifier id = Identifier.tryParse(TierIcons.iconFor(mode));
            if (id != null) {
                Item item = Compat.lookupItem(id);
                ItemStack stack = item == null ? ItemStack.EMPTY : new ItemStack(item);
                if (!stack.isEmpty()) {
                    ctx.drawItem(stack, x, y - 1);
                    textX = x + 20;
                }
            }
        } catch (Throwable ignored) {}

        // Mode label (left)
        String label = TierIcons.labelFor(mode);
        ctx.drawTextWithShadow(this.textRenderer,
            Text.literal(label).withColor(FG_TEXT),
            textX, y + 3, FG_TEXT);

        // Three columns inside the right portion of the row:
        //   [tierA]  [winner]  [tierB]
        int rightStart = x + (w * 50 / 100);   // start of tier columns at ~50% of row width
        int rightEnd   = x + w;
        int colW       = (rightEnd - rightStart) / 3;

        // tier A
        Text tA = tierComp(rA);
        int wA = this.textRenderer.getWidth(tA);
        int aCx = rightStart + colW / 2;
        int aColor = (rA != null && rA.tierLevel > 0) ? TierTaggerCore.argbFor(rA.label()) & 0xFFFFFF : 0x808080;
        if (winner == 'A') fillRect(ctx, aCx - wA / 2 - 3, y + 1, aCx + wA / 2 + 3, y + ROW_H - 1, 0x3000FF66);
        ctx.drawTextWithShadow(this.textRenderer, tA, aCx - wA / 2, y + 3, aColor);

        // winner glyph
        Text wsym;
        switch (winner) {
            case 'A': wsym = Text.literal("\u25C0").formatted(Formatting.GREEN, Formatting.BOLD); break;
            case 'B': wsym = Text.literal("\u25B6").formatted(Formatting.GREEN, Formatting.BOLD); break;
            default:  wsym = Text.literal("=").formatted(Formatting.YELLOW); break;
        }
        ctx.drawCenteredTextWithShadow(this.textRenderer, wsym, rightStart + colW + colW / 2, y + 3, 0xFFFFFF);

        // tier B
        Text tB = tierComp(rB);
        int wB = this.textRenderer.getWidth(tB);
        int bCx = rightStart + colW * 2 + colW / 2;
        int bColor = (rB != null && rB.tierLevel > 0) ? TierTaggerCore.argbFor(rB.label()) & 0xFFFFFF : 0x808080;
        if (winner == 'B') fillRect(ctx, bCx - wB / 2 - 3, y + 1, bCx + wB / 2 + 3, y + ROW_H - 1, 0x3000FF66);
        ctx.drawTextWithShadow(this.textRenderer, tB, bCx - wB / 2, y + 3, bColor);
    }

    private static Text tierComp(Ranking r) {
        if (r == null || r.tierLevel <= 0) return Text.literal("—").formatted(Formatting.DARK_GRAY);
        return Text.literal(r.label()).formatted(Formatting.BOLD);
    }

    private static String regionPair(ServiceData a, ServiceData b) {
        String ra = (a == null || a.region == null || a.region.isBlank()) ? "—" : a.region;
        String rb = (b == null || b.region == null || b.region.isBlank()) ? "—" : b.region;
        return ra + "  vs  " + rb;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void drawHead(DrawContext ctx, String name, int x, int y, int size) {
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

    private static void fillRect(DrawContext ctx, int x1, int y1, int x2, int y2, int argb) {
        try { ctx.fill(x1, y1, x2, y2, argb); } catch (Throwable ignored) {}
    }

    private static void outlineRect(DrawContext ctx, int x, int y, int w, int h, int argb) {
        try {
            ctx.fill(x,         y,         x + w,     y + 1,     argb);
            ctx.fill(x,         y + h - 1, x + w,     y + h,     argb);
            ctx.fill(x,         y,         x + 1,     y + h,     argb);
            ctx.fill(x + w - 1, y,         x + w,     y + h,     argb);
        } catch (Throwable ignored) {}
    }

    private void closeSafely() {
        MinecraftClient mc = this.client != null ? this.client : MinecraftClient.getInstance();
        if (mc != null) mc.setScreen(parent);
    }

    @Override
    public void close() { closeSafely(); }
}

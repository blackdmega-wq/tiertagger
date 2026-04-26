package com.outertiers.tiertagger.fabric.screen;

import com.outertiers.tiertagger.common.PlayerData;
import com.outertiers.tiertagger.common.Ranking;
import com.outertiers.tiertagger.common.ServiceData;
import com.outertiers.tiertagger.common.TierIcons;
import com.outertiers.tiertagger.common.TierService;
import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.fabric.SkinFetcher;
import com.outertiers.tiertagger.fabric.compat.Compat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.PlayerListEntry;
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
 * Side-by-side player comparison.
 *
 *   ┌─────────────── opaque panel ──────────────┐
 *   │  [head] PlayerA              PlayerB [head]│
 *   │   HT2 on Vanilla       HT3 on Sword        │
 *   │                ┌──────────┐                │
 *   │                │    vs    │                │
 *   │                └──────────┘                │
 *   │   ▍ MCTiers                                │
 *   │     [icon] Vanilla    HT2  ◀   HT3         │
 *   │     [icon] Sword      LT3  =   LT3         │
 *   │   ▍ OuterTiers                             │
 *   │     ...                                    │
 *   │   Wins: A 4   B 6   ties 2                 │
 *   │   [↻ A]                            [↻ B]   │
 *   └────────────────────────────────────────────┘
 *
 * Heads use {@link SkinFetcher} so offline players still get a real face,
 * and mode icons use the OuterTiers website PNGs from {@link ModeIcons}.
 */
public class TierCompareScreen extends Screen {

    private static final Identifier STEVE = Identifier.ofVanilla("textures/entity/player/wide/steve.png");

    private static final int PANEL_W_MAX = 580;
    private static final int CARD_GAP    = 6;
    private static final int CARD_PAD    = 8;
    private static final int ROW_H       = 14;
    private static final int ICON_SIZE   = 16;
    /** Extra horizontal breathing room between the right player's name and head. */
    private static final int RIGHT_NAME_GAP = 18;

    private static final int BG_PANEL        = 0xF20E1116;
    private static final int BG_PANEL_BORDER = 0xFF2A2F38;
    private static final int BG_HEADER       = 0xFF181C24;
    private static final int BG_CARD         = 0xFF15191F;
    private static final int BG_CARD_BAR     = 0xFF1E232C;
    // Colours carry the 0xFF alpha byte — MC 1.21.5+ treats alpha == 0 as
    // "fully transparent" inside drawTextWithShadow(), which previously hid
    // every label drawn on this screen.
    private static final int FG_FAINT        = 0xFF9AA0AA;
    private static final int FG_TEXT         = 0xFFE6E8EC;
    private static final int VS_BG           = 0xFF222631;
    private static final int VS_BORDER       = 0xFF3A4150;

    /** Force the high alpha byte on; needed for drawTextWithShadow() colour params. */
    private static int opaque(int rgbOrArgb) { return rgbOrArgb | 0xFF000000; }
    /** Strip alpha to keep Style.withColor(int) inside its required 0..0xFFFFFF range. */
    private static int rgb(int argb) { return argb & 0xFFFFFF; }

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

    /** Set when init() throws, so render() can show a useful overlay instead of a blank screen. */
    private volatile String lastInitError = null;

    @Override
    protected void init() {
        lastInitError = null;
        try { TierTaggerCore.cache().peekData(nameA); } catch (Throwable ignored) {}
        try { TierTaggerCore.cache().peekData(nameB); } catch (Throwable ignored) {}
        // Pre-warm offline avatar downloads.
        try { SkinFetcher.headFor(nameA); SkinFetcher.headFor(nameB); } catch (Throwable ignored) {}
        scrollY = 0;

        int panelW = Math.min(PANEL_W_MAX, this.width - 40);
        int panelX = (this.width - panelW) / 2;
        int btnY   = this.height - 28;

        // Add the Close button FIRST so the user can always escape, even if a
        // later button construction throws.
        try {
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"), btn -> closeSafely())
                .dimensions(this.width / 2 - 40, btnY, 80, 20).build());
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] compare close button failed", t);
        }

        try {
            this.addDrawableChild(ButtonWidget.builder(Text.literal("\u21BB " + nameA), btn -> {
                    try { TierTaggerCore.cache().invalidatePlayer(nameA);
                          TierTaggerCore.cache().peekData(nameA); } catch (Throwable ignored) {}
                })
                .dimensions(panelX + CARD_PAD, btnY, 100, 20).build());
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] compare refresh-A button failed", t);
            lastInitError = "Refresh button failed: " + t.getClass().getSimpleName();
        }

        try {
            this.addDrawableChild(ButtonWidget.builder(Text.literal("\u21BB " + nameB), btn -> {
                    try { TierTaggerCore.cache().invalidatePlayer(nameB);
                          TierTaggerCore.cache().peekData(nameB); } catch (Throwable ignored) {}
                })
                .dimensions(panelX + panelW - CARD_PAD - 100, btnY, 100, 20).build());
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] compare refresh-B button failed", t);
            lastInitError = "Refresh button failed: " + t.getClass().getSimpleName();
        }
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

            // Header sized to fit the 3D-angled player render at a
            // comfortable scale alongside the centered OuterTiers logo.
            // Previously 92px which made the body renders dominate the
            // panel and obscure skin detail — the user explicitly asked
            // for a smaller, angled render here (matches profile screen).
            int headerH = 72;
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

            if (lastInitError != null) {
                drawErrorOverlay(ctx, "Compare init failed", lastInitError);
            }
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] compare render", t);
            try { drawErrorOverlay(ctx, "Compare render failed",
                t.getClass().getSimpleName() + ": " +
                (t.getMessage() == null ? "(no message)" : t.getMessage())); } catch (Throwable ignored) {}
        }
    }

    private void drawErrorOverlay(DrawContext ctx, String title, String detail) {
        int cx = this.width / 2;
        int cy = this.height / 2;
        int w = Math.min(this.width - 40, 420);
        try { ctx.fill(cx - w / 2, cy - 36, cx + w / 2, cy + 36, 0xCC110000); } catch (Throwable ignored) {}
        try {
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(title).formatted(Formatting.RED, Formatting.BOLD), cx, cy - 22, 0xFFFF5555);
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(detail).formatted(Formatting.WHITE), cx, cy - 6, 0xFFFFFFFF);
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("See latest.log for the full stack trace").formatted(Formatting.GRAY),
                cx, cy + 12, 0xFFAAAAAA);
        } catch (Throwable ignored) {}
    }

    // ── header ──────────────────────────────────────────────────────────────

    private void renderHeader(DrawContext ctx, PlayerData dA, PlayerData dB,
                              int x, int y, int w, int h) {
        fillRect(ctx, x, y, x + w, y + h, BG_HEADER);

        // Body slots are tall 1:2-ish rectangles so the full-body skin
        // render fits naturally instead of being squashed into a square.
        int bodyH      = h - 12;
        int bodyW      = bodyH / 2;
        int leftHeadX  = x + 10;
        int rightHeadX = x + w - 10 - bodyW;
        int headY      = y + (h - bodyH) / 2;
        drawHead(ctx, nameA, leftHeadX,  headY, bodyW, bodyH);
        drawHead(ctx, nameB, rightHeadX, headY, bodyW, bodyH);

        // ── Left side text block ──
        int leftTextX = leftHeadX + bodyW + 10;
        ctx.drawTextWithShadow(this.textRenderer,
            Text.literal(nameA).formatted(Formatting.WHITE, Formatting.BOLD),
            leftTextX, y + 10, 0xFFFFFFFF);

        Ranking bestA = highestOverall(dA);
        TierService bestSvcA = highestOverallSvc(dA);
        if (bestA != null) {
            int cArgb = TierTaggerCore.argbFor(bestA.label());
            MutableText t = Text.literal(bestA.label()).withColor(rgb(cArgb)).copy().formatted(Formatting.BOLD)
                .append(Text.literal(" on ").formatted(Formatting.GRAY))
                .append(Text.literal(bestSvcA == null ? "" : bestSvcA.shortLabel).withColor(rgb(FG_TEXT)));
            ctx.drawTextWithShadow(this.textRenderer, t, leftTextX, y + 24, opaque(cArgb));
        } else {
            ctx.drawTextWithShadow(this.textRenderer,
                Text.literal(dA == null ? "loading\u2026" : "no tiers").withColor(rgb(FG_FAINT)),
                leftTextX, y + 24, FG_FAINT);
        }
        // Per-player loaded count
        ctx.drawTextWithShadow(this.textRenderer,
            Text.literal(loadedCount(dA)).withColor(rgb(FG_FAINT)),
            leftTextX, y + 38, FG_FAINT);

        // ── Right side text block (right-aligned, with extra gap from body) ──
        int rightTextRight = rightHeadX - RIGHT_NAME_GAP;
        int wName = this.textRenderer.getWidth(nameB);
        ctx.drawTextWithShadow(this.textRenderer,
            Text.literal(nameB).formatted(Formatting.WHITE, Formatting.BOLD),
            rightTextRight - wName, y + 10, 0xFFFFFFFF);

        Ranking bestB = highestOverall(dB);
        TierService bestSvcB = highestOverallSvc(dB);
        if (bestB != null) {
            int cArgb = TierTaggerCore.argbFor(bestB.label());
            MutableText t = Text.literal(bestB.label()).withColor(rgb(cArgb)).copy().formatted(Formatting.BOLD)
                .append(Text.literal(" on ").formatted(Formatting.GRAY))
                .append(Text.literal(bestSvcB == null ? "" : bestSvcB.shortLabel).withColor(rgb(FG_TEXT)));
            int wt = this.textRenderer.getWidth(t);
            ctx.drawTextWithShadow(this.textRenderer, t, rightTextRight - wt, y + 24, opaque(cArgb));
        } else {
            String s = dB == null ? "loading\u2026" : "no tiers";
            int sw = this.textRenderer.getWidth(s);
            ctx.drawTextWithShadow(this.textRenderer,
                Text.literal(s).withColor(rgb(FG_FAINT)), rightTextRight - sw, y + 24, FG_FAINT);
        }
        String lc = loadedCount(dB);
        int lcw = this.textRenderer.getWidth(lc);
        ctx.drawTextWithShadow(this.textRenderer,
            Text.literal(lc).withColor(rgb(FG_FAINT)), rightTextRight - lcw, y + 38, FG_FAINT);

        // ── Centred OuterTiers logo (replaces the old "vs" pill) ──
        // The logo PNG is bundled at assets/tiertagger/textures/logo/outertiers.png
        // (512×512 source). We fit it inside a square slot scaled to the
        // header so it stays crisp on small windows and big windows alike.
        int logoBox = Math.min(h - 16, 56);
        int logoX   = x + (w - logoBox) / 2;
        int logoY   = y + (h - logoBox) / 2;
        try {
            Identifier logo = Identifier.of("tiertagger", "textures/logo/outertiers.png");
            Compat.drawTexture(ctx, logo, logoX, logoY, 0, 0, logoBox, logoBox, 512, 512);
        } catch (Throwable ignored) {
            // Fall back to the old "vs" pill if for some reason the logo
            // texture can't be drawn — keeps the UI usable.
            String vs = "vs";
            int pillW = 28, pillH = 18;
            int pillX = x + (w - pillW) / 2;
            int pillY = y + (h - pillH) / 2;
            fillRect(ctx, pillX, pillY, pillX + pillW, pillY + pillH, VS_BG);
            outlineRect(ctx, pillX, pillY, pillW, pillH, VS_BORDER);
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(vs).formatted(Formatting.GRAY, Formatting.BOLD),
                pillX + pillW / 2, pillY + 5, 0xCCCCCC);
        }
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

    private static TierService highestOverallSvc(PlayerData d) {
        if (d == null) return null;
        Ranking best = null;
        TierService bestSvc = null;
        for (TierService s : TierService.values()) {
            Ranking r = d.get(s).highest();
            if (r == null) continue;
            if (best == null || r.score() > best.score()) { best = r; bestSvc = s; }
        }
        return bestSvc;
    }

    private static String loadedCount(PlayerData d) {
        if (d == null) return "loading\u2026";
        int loaded = 0, ranked = 0;
        for (TierService s : TierService.values()) {
            ServiceData sd = d.get(s);
            if (sd.fetchedAt > 0) loaded++;
            if (sd.rankedCount() > 0) ranked++;
        }
        return ranked + " / " + TierService.values().length + " ranked";
    }

    // ── cards ───────────────────────────────────────────────────────────────

    private int renderCards(DrawContext ctx, PlayerData dA, PlayerData dB, int x, int y, int w) {
        int wins1 = 0, wins2 = 0, ties = 0;

        for (TierService svc : TierService.values()) {
            ServiceData sdA = dA == null ? null : dA.get(svc);
            ServiceData sdB = dB == null ? null : dB.get(svc);

            Set<String> allModes = new LinkedHashSet<>(svc.modes);
            if (sdA != null) allModes.addAll(sdA.rankings.keySet());
            if (sdB != null) allModes.addAll(sdB.rankings.keySet());
            allModes.removeIf(m -> isModeHidden(svc, m));

            // Always render every mode the service exposes (even when neither
            // player is ranked) so users can see e.g. NethOP / Netherite Pot /
            // Crystal slots — previously hidden modes confused users into
            // thinking they were missing from the mod.
            int rowsToDraw = allModes.size();
            int cardH = 22 + Math.max(1, rowsToDraw) * ROW_H + 6;

            fillRect(ctx, x, y, x + w, y + cardH, BG_CARD);
            outlineRect(ctx, x, y, w, cardH, 0xFF2A2F38);
            fillRect(ctx, x, y, x + 3, y + cardH, svc.accentArgb);
            fillRect(ctx, x + 3, y, x + w, y + 22, BG_CARD_BAR);

            ctx.drawTextWithShadow(this.textRenderer,
                Text.literal(svc.shortLabel).withColor(rgb(svc.accentArgb)).copy().formatted(Formatting.BOLD),
                x + 10, y + 7, opaque(svc.accentArgb));
            ctx.drawTextWithShadow(this.textRenderer,
                Text.literal(svc.displayName).formatted(Formatting.WHITE),
                x + 10 + this.textRenderer.getWidth(svc.shortLabel) + 6, y + 7, FG_TEXT);

            String regs = regionPair(sdA, sdB);
            int rsw = this.textRenderer.getWidth(regs);
            ctx.drawTextWithShadow(this.textRenderer, Text.literal(regs).withColor(rgb(FG_FAINT)),
                x + w - 10 - rsw, y + 7, FG_FAINT);

            int rowY = y + 24;
            {
                boolean alt = false;
                for (String mode : allModes) {
                    Ranking rA = sdA == null ? null : sdA.rankings.get(mode);
                    Ranking rB = sdB == null ? null : sdB.rankings.get(mode);
                    boolean hasA = rA != null && rA.tierLevel > 0;
                    boolean hasB = rB != null && rB.tierLevel > 0;

                    int sA = hasA ? rA.score() : -1;
                    int sB = hasB ? rB.score() : -1;
                    char winner;
                    if (!hasA && !hasB) {
                        // Neither ranked — render an info row but don't count
                        // it toward the win/loss/tie footer summary.
                        winner = '-';
                    } else if (sA > sB) { winner = 'A'; wins1++; }
                    else if (sB > sA)   { winner = 'B'; wins2++; }
                    else                { winner = '='; ties++; }

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
        ctx.drawCenteredTextWithShadow(this.textRenderer, sum, x + w / 2, y + 7, 0xFFFFFFFF);
        return y + 26;
    }

    private void renderCmpRow(DrawContext ctx, String mode, Ranking rA, Ranking rB,
                              char winner, int x, int y, int w, boolean alt) {
        if (alt) fillRect(ctx, x - 4, y, x + w + 4, y + ROW_H, 0x14FFFFFF);

        int textX = x;
        boolean drewIcon = false;
        Identifier tex = ModeIcons.textureFor(mode);
        if (tex != null) {
            try {
                Compat.drawTexture(ctx, tex, x, y - 1, 0, 0, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
                drewIcon = true;
            } catch (Throwable ignored) {}
        }
        if (!drewIcon) {
            try {
                Identifier id = Identifier.tryParse(TierIcons.iconFor(mode));
                if (id != null) {
                    Item item = Compat.lookupItem(id);
                    ItemStack stack = item == null ? ItemStack.EMPTY : new ItemStack(item);
                    if (!stack.isEmpty()) {
                        ctx.drawItem(stack, x, y - 1);
                        drewIcon = true;
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (drewIcon) textX = x + ICON_SIZE + 4;

        String label = TierIcons.labelFor(mode);
        ctx.drawTextWithShadow(this.textRenderer,
            Text.literal(label).withColor(rgb(FG_TEXT)),
            textX, y + 3, FG_TEXT);

        // Three columns inside the right portion of the row:
        //   [tierA]  [winner]  [tierB]
        int rightStart = x + (w * 50 / 100);
        int rightEnd   = x + w;
        int colW       = (rightEnd - rightStart) / 3;

        Text tA = tierComp(rA, (rA != null && rA.tierLevel > 0) ? TierTaggerCore.argbFor(rA.label()) : 0xFF808080);
        int wA = this.textRenderer.getWidth(tA);
        int aCx = rightStart + colW / 2;
        int aColor = (rA != null && rA.tierLevel > 0) ? opaque(TierTaggerCore.argbFor(rA.label())) : 0xFF808080;
        if (winner == 'A') fillRect(ctx, aCx - wA / 2 - 3, y + 1, aCx + wA / 2 + 3, y + ROW_H - 1, 0x3000FF66);
        ctx.drawTextWithShadow(this.textRenderer, tA, aCx - wA / 2, y + 3, aColor);

        Text wsym;
        switch (winner) {
            case 'A': wsym = Text.literal("\u25C0").formatted(Formatting.GREEN, Formatting.BOLD); break;
            case 'B': wsym = Text.literal("\u25B6").formatted(Formatting.GREEN, Formatting.BOLD); break;
            case '=': wsym = Text.literal("=").formatted(Formatting.YELLOW); break;
            default:  wsym = Text.literal("\u2014").formatted(Formatting.DARK_GRAY); break; // neither ranked
        }
        ctx.drawCenteredTextWithShadow(this.textRenderer, wsym, rightStart + colW + colW / 2, y + 3, 0xFFFFFFFF);

        Text tB = tierComp(rB, (rB != null && rB.tierLevel > 0) ? TierTaggerCore.argbFor(rB.label()) : 0xFF808080);
        int wB = this.textRenderer.getWidth(tB);
        int bCx = rightStart + colW * 2 + colW / 2;
        int bColor = (rB != null && rB.tierLevel > 0) ? opaque(TierTaggerCore.argbFor(rB.label())) : 0xFF808080;
        if (winner == 'B') fillRect(ctx, bCx - wB / 2 - 3, y + 1, bCx + wB / 2 + 3, y + ROW_H - 1, 0x3000FF66);
        ctx.drawTextWithShadow(this.textRenderer, tB, bCx - wB / 2, y + 3, bColor);
    }

    private static Text tierComp(Ranking r, int argb) {
        if (r == null || r.tierLevel <= 0) return Text.literal("\u2014").formatted(Formatting.DARK_GRAY);
        return Text.literal(r.label()).withColor(argb & 0xFFFFFF).copy().formatted(Formatting.BOLD);
    }

    /**
     * Per-service mode blacklist requested by the user: hide gamemodes that
     * either duplicate a mainstream mode or aren't actively maintained on
     * that tier list.
     *   MCTiers  — drop NethPot (keep NethOP)
     *   PvPTiers — drop NethOP AND Vanilla (Vanilla == Crystal on PvPTiers; only show Crystal)
     *   SubTiers — drop Dia 2v2
     *
     * IMPORTANT: "nethpot" (Netherite Pot) and "nethop" (NethOP) are TWO
     * DISTINCT gamemodes. Do NOT lump them together.
     */
    private static boolean isModeHidden(TierService svc, String mode) {
        if (mode == null) return false;
        String m = mode.toLowerCase(java.util.Locale.ROOT);
        switch (svc) {
            case MCTIERS:
                return m.equals("nethpot") || m.equals("neth_pot");
            case PVPTIERS:
                // Hide NethOP and Vanilla. On PvPTiers "vanilla" is Crystal PvP —
                // we show it under the "crystal" key only to avoid the duplicate.
                return m.equals("nethop") || m.equals("vanilla");
            case SUBTIERS:
                return m.equals("dia_2v2") || m.equals("dia2v2") || m.equals("2v2");
            default:
                return false;
        }
    }

    private static String regionPair(ServiceData a, ServiceData b) {
        String ra = (a == null || a.region == null || a.region.isBlank()) ? "\u2014" : a.region;
        String rb = (b == null || b.region == null || b.region.isBlank()) ? "\u2014" : b.region;
        return ra + "  vs  " + rb;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void drawHead(DrawContext ctx, String name, int x, int y, int boxW, int boxH) {
        ctx.fill(x - 2, y - 2, x + boxW + 2, y + boxH + 2, 0xFF1A1A1A);

        // Use mc-heads.net via SkinFetcher for ALL players (online + offline).
        // The /body/ endpoint returns a 2D full-body render (~1:2 aspect)
        // matching the OuterTiers website player cards. We honour the actual
        // decoded image dimensions so the body keeps its real aspect — fit
        // inside the box, centered, no stretching.
        Optional<SkinFetcher.Skin> fetched = Optional.empty();
        try { fetched = SkinFetcher.skinFor(name); } catch (Throwable ignored) {}
        if (fetched.isPresent()) {
            try {
                SkinFetcher.Skin sk = fetched.get();
                int iw = Math.max(1, sk.width);
                int ih = Math.max(1, sk.height);
                double sx = (double) boxW / iw;
                double sy = (double) boxH / ih;
                double scale = Math.min(sx, sy);
                int dw = Math.max(1, (int) Math.floor(iw * scale));
                int dh = Math.max(1, (int) Math.floor(ih * scale));
                int dx = x + (boxW - dw) / 2;
                int dy = y + (boxH - dh) / 2;
                Compat.drawTexture(ctx, sk.id, dx, dy, 0, 0, dw, dh, iw, ih);
                return;
            } catch (Throwable ignored) {}
        }

        // Placeholder while the body render is downloading or if
        // mc-heads.net is unreachable. Stable layout, no empty box.
        try {
            ctx.fill(x, y, x + boxW, y + boxH, 0xFF26303B);
            int cx = x + boxW / 2;
            int hSize = Math.max(4, boxW / 2);
            ctx.fill(cx - hSize / 2, y + 4, cx + hSize / 2, y + 4 + hSize, 0xFF6E4A2A);
            ctx.fill(cx - hSize, y + 6 + hSize, cx + hSize, y + boxH - 4, 0xFF3F2A18);
        } catch (Throwable ignored) {}
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

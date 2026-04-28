package com.outertiers.tiertagger.fabric.screen;

import com.outertiers.tiertagger.common.PlayerData;
import com.outertiers.tiertagger.common.Ranking;
import com.outertiers.tiertagger.common.ServiceData;
import com.outertiers.tiertagger.common.TierIcons;
import com.outertiers.tiertagger.common.TierService;
import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.fabric.SkinFetcher;
import com.outertiers.tiertagger.fabric.compat.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.ResourceLocation;

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

    private static final ResourceLocation STEVE = ResourceLocation.ofVanilla("textures/entity/player/wide/steve.png");

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

    // ── Animation helpers (v1.21.11.55) ────────────────────────────────
    private static float pulse01() {
        long t = System.currentTimeMillis() % 2400L;
        float f = (float) (t / 2400.0);
        return 0.5f + 0.5f * (float) Math.sin(f * Math.PI * 2.0);
    }
    private static int withAlpha(int argb, int alpha) {
        return ((alpha & 0xFF) << 24) | (argb & 0x00FFFFFF);
    }

    private final Screen parent;
    private final String nameA;
    private final String nameB;
    /** Optional filter — when non-null, only this service's card is rendered.
     *  Set by {@code /tiertagger compare <a> <b> [tierlist]}. */
    private final TierService onlyService;
    private boolean bgApplied = false;
    private int scrollY = 0;
    private int maxScroll = 0;

    public TierCompareScreen(Screen parent, String nameA, String nameB) {
        this(parent, nameA, nameB, null);
    }

    public TierCompareScreen(Screen parent, String nameA, String nameB, TierService onlyService) {
        super(Component.literal(nameA + " vs " + nameB
            + (onlyService != null ? "  (" + onlyService.shortLabel + " only)" : "")));
        this.parent      = parent;
        this.nameA       = nameA == null ? "" : nameA;
        this.nameB       = nameB == null ? "" : nameB;
        this.onlyService = onlyService;
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
            this.addDrawableChild(Button.builder(Component.literal("Close"), btn -> closeSafely())
                .dimensions(this.width / 2 - 40, btnY, 80, 20).build());
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] compare close button failed", t);
        }

        try {
            this.addDrawableChild(Button.builder(Component.literal("\u21BB " + nameA), btn -> {
                    try { TierTaggerCore.cache().invalidatePlayer(nameA);
                          TierTaggerCore.cache().peekData(nameA); } catch (Throwable ignored) {}
                })
                .dimensions(panelX + CARD_PAD, btnY, 100, 20).build());
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] compare refresh-A button failed", t);
            lastInitError = "Refresh button failed: " + t.getClass().getSimpleName();
        }

        try {
            this.addDrawableChild(Button.builder(Component.literal("\u21BB " + nameB), btn -> {
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
    public void renderBackground(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        if (bgApplied) return;
        bgApplied = true;
        try { super.renderBackground(ctx, mouseX, mouseY, delta); } catch (Throwable ignored) {}
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        bgApplied = false;
        try {
            this.renderBackground(ctx, mouseX, mouseY, delta);

            int panelW = Math.min(PANEL_W_MAX, this.width - 40);
            int panelX = (this.width - panelW) / 2;
            int panelTop = 18;
            int panelBottom = this.height - 36;

            // (v1.21.11.55) Animated drop-shadow + breathing outline.
            float pulse = pulse01();
            int shadowA = (int)(40 + 40 * pulse);
            for (int i = 4; i >= 1; i--) {
                int a = Math.max(0, shadowA - i * 8);
                fillRect(ctx, panelX - i, panelTop - i, panelX + panelW + i, panelTop, withAlpha(0x0090E1FF, a));
                fillRect(ctx, panelX - i, panelBottom, panelX + panelW + i, panelBottom + i, withAlpha(0x0090E1FF, a));
                fillRect(ctx, panelX - i, panelTop, panelX, panelBottom, withAlpha(0x0090E1FF, a));
                fillRect(ctx, panelX + panelW, panelTop, panelX + panelW + i, panelBottom, withAlpha(0x0090E1FF, a));
            }
            fillRect(ctx, panelX, panelTop, panelX + panelW, panelBottom, BG_PANEL);
            outlineRect(ctx, panelX, panelTop, panelW, panelBottom - panelTop, BG_PANEL_BORDER);
            int borderA = (int)(120 + 120 * pulse);
            outlineRect(ctx, panelX, panelTop, panelW, panelBottom - panelTop,
                        withAlpha(0x0090E1FF, borderA));

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

    private void drawErrorOverlay(GuiGraphics ctx, String title, String detail) {
        int cx = this.width / 2;
        int cy = this.height / 2;
        int w = Math.min(this.width - 40, 420);
        try { ctx.fill(cx - w / 2, cy - 36, cx + w / 2, cy + 36, 0xCC110000); } catch (Throwable ignored) {}
        try {
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                Component.literal(title).formatted(ChatFormatting.RED, ChatFormatting.BOLD), cx, cy - 22, 0xFFFF5555);
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                Component.literal(detail).formatted(ChatFormatting.WHITE), cx, cy - 6, 0xFFFFFFFF);
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                Component.literal("See latest.log for the full stack trace").formatted(ChatFormatting.GRAY),
                cx, cy + 12, 0xFFAAAAAA);
        } catch (Throwable ignored) {}
    }

    // ── header ──────────────────────────────────────────────────────────────

    private void renderHeader(GuiGraphics ctx, PlayerData dA, PlayerData dB,
                              int x, int y, int w, int h) {
        fillRect(ctx, x, y, x + w, y + h, BG_HEADER);

        // (v1.21.11.55) Animated diagonal sheen sweep across the header.
        long tt = System.currentTimeMillis() % 5000L;
        float sweep = tt / 5000f;
        int sx = x + (int)(sweep * (w + 60)) - 60;
        for (int i = 0; i < 24; i++) {
            int a = Math.max(0, 18 - Math.abs(i - 12) * 2);
            fillRect(ctx, sx + i, y, sx + i + 1, y + h, withAlpha(0x00FFFFFF, a));
        }

        // Square skin slots — both player skins face each other inside a
        // TRANSPARENT box, right-hand skin horizontally mirrored.
        int bodyH      = h - 8;
        int bodyW      = bodyH;            // square box
        int leftHeadX  = x + 10;
        int rightHeadX = x + w - 10 - bodyW;
        int headY      = y + (h - bodyH) / 2;

        // (v1.21.11.55) Pulsing halos behind both player skins —
        // contrasting tints so each side reads as its own "team".
        float pulse = pulse01();
        int haloA = (int)(30 + 50 * pulse);
        for (int i = 6; i >= 1; i--) {
            int a = Math.max(0, haloA - i * 6);
            fillRect(ctx, leftHeadX  - i, headY - i, leftHeadX  + bodyW + i, headY + bodyH + i,
                     withAlpha(0x004FA8FF, a));
            fillRect(ctx, rightHeadX - i, headY - i, rightHeadX + bodyW + i, headY + bodyH + i,
                     withAlpha(0x00FF8C5A, a));
        }

        drawHead(ctx, nameA, leftHeadX,  headY, bodyW, bodyH, false);
        drawHead(ctx, nameB, rightHeadX, headY, bodyW, bodyH, true);

        // ── Left side text block ──
        int leftTextX = leftHeadX + bodyW + 10;
        ctx.drawTextWithShadow(this.textRenderer,
            Component.literal(nameA).formatted(ChatFormatting.WHITE, ChatFormatting.BOLD),
            leftTextX, y + 10, 0xFFFFFFFF);

        Ranking bestA = highestOverall(dA);
        TierService bestSvcA = highestOverallSvc(dA);
        if (bestA != null) {
            int cArgb = TierTaggerCore.argbFor(bestA.label());
            MutableComponent t = Component.literal(bestA.label()).withColor(rgb(cArgb)).copy().formatted(ChatFormatting.BOLD)
                .append(Component.literal(" on ").formatted(ChatFormatting.GRAY))
                .append(Component.literal(bestSvcA == null ? "" : bestSvcA.shortLabel).withColor(rgb(FG_TEXT)));
            ctx.drawTextWithShadow(this.textRenderer, t, leftTextX, y + 24, opaque(cArgb));
        } else {
            ctx.drawTextWithShadow(this.textRenderer,
                Component.literal(dA == null ? "loading\u2026" : "no tiers").withColor(rgb(FG_FAINT)),
                leftTextX, y + 24, FG_FAINT);
        }
        // OuterTiers rank for player A (v1.21.11.58 — peak moved into the
        // per-mode rows below as "▲HT3" next to each gamemode's current
        // tier, so the header stays compact and lined up with the
        // opposite player's header.)
        ServiceData otSdA = dA == null ? null : dA.get(TierService.OUTERTIERS);
        if (otSdA != null && otSdA.overall > 0) {
            MutableComponent ot = Component.literal("OT ").withColor(rgb(FG_FAINT))
                .append(Component.literal("#" + otSdA.overall)
                    .withColor(rgb(TierService.OUTERTIERS.accentArgb))
                    .copy().withStyle(ChatFormatting.BOLD));
            ctx.drawString(this.textRenderer, ot, leftTextX, y + 38, FG_TEXT, true);
        }
        // Per-player loaded count
        ctx.drawTextWithShadow(this.textRenderer,
            Component.literal(loadedCount(dA)).withColor(rgb(FG_FAINT)),
            leftTextX, y + 50, FG_FAINT);

        // ── Right side text block (right-aligned, with extra gap from body) ──
        int rightTextRight = rightHeadX - RIGHT_NAME_GAP;
        int wName = this.textRenderer.getWidth(nameB);
        ctx.drawTextWithShadow(this.textRenderer,
            Component.literal(nameB).formatted(ChatFormatting.WHITE, ChatFormatting.BOLD),
            rightTextRight - wName, y + 10, 0xFFFFFFFF);

        Ranking bestB = highestOverall(dB);
        TierService bestSvcB = highestOverallSvc(dB);
        if (bestB != null) {
            int cArgb = TierTaggerCore.argbFor(bestB.label());
            MutableComponent t = Component.literal(bestB.label()).withColor(rgb(cArgb)).copy().formatted(ChatFormatting.BOLD)
                .append(Component.literal(" on ").formatted(ChatFormatting.GRAY))
                .append(Component.literal(bestSvcB == null ? "" : bestSvcB.shortLabel).withColor(rgb(FG_TEXT)));
            int wt = this.textRenderer.getWidth(t);
            ctx.drawTextWithShadow(this.textRenderer, t, rightTextRight - wt, y + 24, opaque(cArgb));
        } else {
            String s = dB == null ? "loading\u2026" : "no tiers";
            int sw = this.textRenderer.getWidth(s);
            ctx.drawTextWithShadow(this.textRenderer,
                Component.literal(s).withColor(rgb(FG_FAINT)), rightTextRight - sw, y + 24, FG_FAINT);
        }
        // OuterTiers rank for player B (v1.21.11.58 — peak moved into the
        // per-mode rows below as "▲HT3" next to each gamemode's current
        // tier, so the header stays compact and lined up with the
        // opposite player's header.)
        ServiceData otSdB = dB == null ? null : dB.get(TierService.OUTERTIERS);
        if (otSdB != null && otSdB.overall > 0) {
            MutableComponent ot = Component.literal("OT ").withColor(rgb(FG_FAINT))
                .append(Component.literal("#" + otSdB.overall)
                    .withColor(rgb(TierService.OUTERTIERS.accentArgb))
                    .copy().withStyle(ChatFormatting.BOLD));
            int wot = this.textRenderer.getWidth(ot);
            ctx.drawString(this.textRenderer, ot, rightTextRight - wot, y + 38, FG_TEXT, true);
        }
        String lc = loadedCount(dB);
        int lcw = this.textRenderer.getWidth(lc);
        ctx.drawTextWithShadow(this.textRenderer,
            Component.literal(lc).withColor(rgb(FG_FAINT)), rightTextRight - lcw, y + 50, FG_FAINT);

        // ── Centred OuterTiers logo (replaces the old "vs" pill) ──
        // The logo PNG is bundled at assets/tiertagger/textures/logo/outertiers.png
        // (512×512 source). We fit it inside a square slot scaled to the
        // header so it stays crisp on small windows and big windows alike.
        // (v1.21.11.56) The slot is anchored to BOTH axes (mathematical
        // centre of the header rect) so the logo always sits perfectly
        // in the middle — previously the loop was geometrically centred
        // but the halo bled over the logo, making the icon look offset.
        int logoBox = Math.min(h - 12, 64);
        int logoX   = x + (w - logoBox) / 2;
        int logoY   = y + (h - logoBox) / 2;
        // (v1.21.11.56) The halo is rendered as concentric BORDER rings
        // (top + bottom + left + right edges only) — never as filled
        // rectangles — so the golden glow stays OUTSIDE the logo slot.
        // The previous filled-rect halo painted ON TOP of the logo area;
        // since the logo PNG is mostly transparent (the artwork only
        // occupies the centre of its 512×512 canvas), the user saw an
        // empty golden square with no icon at all. Border-only halo
        // keeps the logo art unobscured and the glow visibly outside.
        int logoHaloA = (int)(60 + 80 * pulse);
        for (int i = 8; i >= 1; i--) {
            int a = Math.max(0, logoHaloA - i * 7);
            int col = withAlpha(0x00FFE26B, a);
            // Top and bottom edges (1 px tall each).
            fillRect(ctx, logoX - i, logoY - i,         logoX + logoBox + i, logoY - i + 1,             col);
            fillRect(ctx, logoX - i, logoY + logoBox + i - 1, logoX + logoBox + i, logoY + logoBox + i, col);
            // Left and right edges (1 px wide each, between top/bottom).
            fillRect(ctx, logoX - i,             logoY - i + 1, logoX - i + 1,             logoY + logoBox + i - 1, col);
            fillRect(ctx, logoX + logoBox + i - 1, logoY - i + 1, logoX + logoBox + i,     logoY + logoBox + i - 1, col);
        }
        // Subtle dark backdrop fades behind the logo so dark icon details
        // stay readable on bright header gradients (≈10% opaque charcoal).
        fillRect(ctx, logoX, logoY, logoX + logoBox, logoY + logoBox, 0x33000000);
        try {
            ResourceLocation logo = ResourceLocation.fromNamespaceAndPath("tiertagger", "textures/logo/outertiers.png");
            // Apply a small inset so the artwork breathes inside the halo
            // ring instead of touching the very edge of the box.
            int inset    = 2;
            int drawSize = logoBox - inset * 2;
            // v1.21.11.58 fix: passing texW/texH = 512 made the 9-arg
            // drawTexture overload sample only the top-left 60×60 pixels
            // of the 512×512 source — almost entirely transparent on the
            // OuterTiers logo, so the gold halo appeared empty. Mirror
            // drawSize into texW/texH so the full texture maps into the
            // slot, matching the pattern used by every other texture call
            // in this codebase (see TierConfigScreen header logo).
            Compat.drawTexture(ctx, logo,
                logoX + inset, logoY + inset, 0, 0,
                drawSize, drawSize, drawSize, drawSize);
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
                Component.literal(vs).formatted(ChatFormatting.GRAY, ChatFormatting.BOLD),
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

    /** v1.21.11.57: strongest peak across every service, or null. */
    private static Ranking peakOverall(PlayerData d) {
        if (d == null) return null;
        Ranking best = null;
        for (TierService s : TierService.values()) {
            Ranking r = d.get(s).peak();
            if (r == null) continue;
            int rs = (6 - r.peakLevel) * 2 - (r.peakHigh ? 0 : 1);
            int bs = best == null ? -1
                   : (6 - best.peakLevel) * 2 - (best.peakHigh ? 0 : 1);
            if (rs > bs) best = r;
        }
        return best;
    }

    private static TierService peakOverallSvc(PlayerData d) {
        if (d == null) return null;
        Ranking best = null;
        TierService bestSvc = null;
        for (TierService s : TierService.values()) {
            Ranking r = d.get(s).peak();
            if (r == null) continue;
            int rs = (6 - r.peakLevel) * 2 - (r.peakHigh ? 0 : 1);
            int bs = best == null ? -1
                   : (6 - best.peakLevel) * 2 - (best.peakHigh ? 0 : 1);
            if (rs > bs) { best = r; bestSvc = s; }
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

    private int renderCards(GuiGraphics ctx, PlayerData dA, PlayerData dB, int x, int y, int w) {
        int wins1 = 0, wins2 = 0, ties = 0;

        for (TierService svc : TierService.values()) {
            // /tiertagger compare <a> <b> <tierlist> → only render that one card.
            if (onlyService != null && svc != onlyService) continue;
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
                Component.literal(svc.shortLabel).withColor(rgb(svc.accentArgb)).copy().formatted(ChatFormatting.BOLD),
                x + 10, y + 7, opaque(svc.accentArgb));
            ctx.drawTextWithShadow(this.textRenderer,
                Component.literal(svc.displayName).formatted(ChatFormatting.WHITE),
                x + 10 + this.textRenderer.getWidth(svc.shortLabel) + 6, y + 7, FG_TEXT);

            String regs = regionPair(sdA, sdB);
            int rsw = this.textRenderer.getWidth(regs);
            ctx.drawTextWithShadow(this.textRenderer, Component.literal(regs).withColor(rgb(FG_FAINT)),
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

                    renderCmpRow(ctx, svc, mode, rA, rB, winner, x + 10, rowY, w - 20, alt);
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
        MutableComponent sum = Component.literal("Wins  ").formatted(ChatFormatting.GRAY)
            .append(Component.literal(nameA + ": ").formatted(ChatFormatting.WHITE))
            .append(Component.literal(String.valueOf(wins1)).formatted(ChatFormatting.GREEN, ChatFormatting.BOLD))
            .append(Component.literal("    " + nameB + ": ").formatted(ChatFormatting.WHITE))
            .append(Component.literal(String.valueOf(wins2)).formatted(ChatFormatting.GREEN, ChatFormatting.BOLD))
            .append(Component.literal("    ties: ").formatted(ChatFormatting.WHITE))
            .append(Component.literal(String.valueOf(ties)).formatted(ChatFormatting.YELLOW, ChatFormatting.BOLD));
        ctx.drawCenteredTextWithShadow(this.textRenderer, sum, x + w / 2, y + 7, 0xFFFFFFFF);
        return y + 26;
    }

    private void renderCmpRow(GuiGraphics ctx, TierService svc, String mode, Ranking rA, Ranking rB,
                              char winner, int x, int y, int w, boolean alt) {
        if (alt) fillRect(ctx, x - 4, y, x + w + 4, y + ROW_H, 0x14FFFFFF);

        int textX = x;
        boolean drewIcon = false;
        // Pass the service id so per-service overrides (PvPTiers art for
        // sword/uhc/pot/…) win over the shared MCTiers/OuterTiers icon set.
        ResourceLocation tex = ModeIcons.textureFor(svc == null ? null : svc.id, mode);
        if (tex != null) {
            try {
                Compat.drawTexture(ctx, tex, x, y - 1, 0, 0, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
                drewIcon = true;
            } catch (Throwable ignored) {}
        }
        if (!drewIcon) {
            try {
                ResourceLocation id = ResourceLocation.tryParse(TierIcons.iconFor(mode));
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
            Component.literal(label).withColor(rgb(FG_TEXT)),
            textX, y + 3, FG_TEXT);

        // Three columns inside the right portion of the row:
        //   [tierA]  [winner]  [tierB]
        int rightStart = x + (w * 50 / 100);
        int rightEnd   = x + w;
        int colW       = (rightEnd - rightStart) / 3;

        // Build "current ▲peak" when the player has reached a stronger tier
        // before. Mirrors TierProfileScreen.renderRowEx() so /tiertagger
        // compare and /tiertagger profile show the same peak indicator.
        MutableComponent tA = tierWithPeak(rA);
        int wA = this.textRenderer.getWidth(tA);
        int aCx = rightStart + colW / 2;
        int aColor = (rA != null && rA.tierLevel > 0) ? opaque(TierTaggerCore.argbFor(rA.label())) : 0xFF808080;
        if (winner == 'A') fillRect(ctx, aCx - wA / 2 - 3, y + 1, aCx + wA / 2 + 3, y + ROW_H - 1, 0x3000FF66);
        ctx.drawTextWithShadow(this.textRenderer, tA, aCx - wA / 2, y + 3, aColor);

        Component wsym;
        switch (winner) {
            case 'A': wsym = Component.literal("\u25C0").formatted(ChatFormatting.GREEN, ChatFormatting.BOLD); break;
            case 'B': wsym = Component.literal("\u25B6").formatted(ChatFormatting.GREEN, ChatFormatting.BOLD); break;
            case '=': wsym = Component.literal("=").formatted(ChatFormatting.YELLOW); break;
            default:  wsym = Component.literal("\u2014").formatted(ChatFormatting.DARK_GRAY); break; // neither ranked
        }
        ctx.drawCenteredTextWithShadow(this.textRenderer, wsym, rightStart + colW + colW / 2, y + 3, 0xFFFFFFFF);

        MutableComponent tB = tierWithPeak(rB);
        int wB = this.textRenderer.getWidth(tB);
        int bCx = rightStart + colW * 2 + colW / 2;
        int bColor = (rB != null && rB.tierLevel > 0) ? opaque(TierTaggerCore.argbFor(rB.label())) : 0xFF808080;
        if (winner == 'B') fillRect(ctx, bCx - wB / 2 - 3, y + 1, bCx + wB / 2 + 3, y + ROW_H - 1, 0x3000FF66);
        ctx.drawTextWithShadow(this.textRenderer, tB, bCx - wB / 2, y + 3, bColor);
    }

    private static MutableComponent tierComp(Ranking r, int argb) {
        if (r == null || r.tierLevel <= 0) return Component.literal("\u2014").formatted(ChatFormatting.DARK_GRAY);
        return Component.literal(r.label()).withColor(argb & 0xFFFFFF).copy().formatted(ChatFormatting.BOLD);
    }

    /**
     * Render a per-mode tier as "{cur} ▲{peak}" when the player's peak
     * tier is strictly stronger than their current tier (e.g. "LT3 ▲HT3").
     * Falls back to just the current tier (or em-dash when unranked).
     * Mirrors the peak indicator used by TierProfileScreen so the compare
     * and search screens read identically.
     */
    private static MutableComponent tierWithPeak(Ranking r) {
        int curArgb = (r != null && r.tierLevel > 0)
            ? TierTaggerCore.argbFor(r.label()) : 0xFF808080;
        MutableComponent out = tierComp(r, curArgb);
        if (r == null || r.tierLevel <= 0 || !r.peakDiffers()) return out;
        String peak = r.peakLabel();
        int peakArgb = TierTaggerCore.argbFor(peak);
        return out.append(Component.literal(" \u25B2").formatted(ChatFormatting.DARK_GRAY))
                  .append(Component.literal(peak).withColor(peakArgb & 0xFFFFFF));
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

    /**
     * Render a player's full body inside a transparent square slot.
     * v1.21.11.37: dropped the dark backdrop fill (was 0xFF1A1A1A) so the
     * box is now fully transparent — only the skin pixels are visible.
     * The skin is anchored to the BOTTOM of the slot (feet touch the
     * floor) and, when {@code mirror} is true, is horizontally flipped so
     * the right-hand player visually faces the left-hand player.
     */
    private void drawHead(GuiGraphics ctx, String name, int x, int y,
                          int boxW, int boxH, boolean mirror) {
        // v1.21.11.39: render the TOP HALF of the skin (head + chest +
        // arms) cropped via UV math, scaled to fill the SQUARE box, and
        // anchored to the bottom so its baseline (the waist cut) sits
        // flush against the bottom edge of the box — no gap. The
        // {@code mirror} flag is intentionally ignored: the matrix-mirror
        // trick used in v1.21.11.37/38 was unreliable across the
        // Matrix3x2fStack mapping shifts (which is why the right-hand
        // skin sometimes vanished entirely). Both compare slots now
        // render the same orientation, which the user explicitly
        // accepted in the v1.21.11.39 brief.
        Optional<SkinFetcher.Skin> fetched = Optional.empty();
        try { fetched = SkinFetcher.skinFor(name); } catch (Throwable ignored) {}
        if (fetched.isPresent()) {
            try {
                SkinFetcher.Skin sk = fetched.get();
                int iw = Math.max(1, sk.width);
                int ih = Math.max(1, sk.height);
                int halfH = Math.max(1, ih / 2);
                // Fit-inside scaling for the TOP HALF (iw × halfH). The
                // half-image aspect ≈ iw/halfH ≈ 2*iw/ih ≈ 0.83, so it
                // sits comfortably inside a square box.
                double scale = Math.min((double) boxW / iw, (double) boxH / halfH);
                int dw = Math.max(1, (int) Math.floor(iw * scale));
                int dh = Math.max(1, (int) Math.floor(halfH * scale));
                int dx = x + (boxW - dw) / 2;
                int dy = y + (boxH - dh);     // anchor to bottom (waist cut at floor)
                // textureHeight = 2*dh tells MC the texture is twice the
                // displayed height, so the (u=0, v=0, w=dw, h=dh) sub-rect
                // samples only the TOP HALF of the source image.
                Compat.drawTexture(ctx, sk.id, dx, dy, 0, 0, dw, dh, dw, dh * 2);
                return;
            } catch (Throwable ignored) {}
        }

        // Loading placeholder — head + chest + arms only (matches the
        // top-half crop), anchored to the bottom of the box so the
        // layout doesn't shift when the real skin arrives.
        try {
            int cx     = x + boxW / 2;
            int botY   = y + boxH;
            int torsoH = Math.max(12, boxH * 9 / 16);
            int headH  = Math.max(10, boxH * 7 / 16);
            int torsoTop = botY - torsoH;
            int headBot  = torsoTop;
            int headTop  = headBot - headH;
            ctx.fill(cx - 8, torsoTop, cx + 8, botY,     0xFF4A6FA5);
            ctx.fill(cx - 12, torsoTop, cx - 8, botY,    0xFFB78462);
            ctx.fill(cx + 8,  torsoTop, cx + 12, botY,   0xFFB78462);
            ctx.fill(cx - 6, headTop,  cx + 6, headBot,  0xFFB78462);
        } catch (Throwable ignored) {}
    }

    private static void fillRect(GuiGraphics ctx, int x1, int y1, int x2, int y2, int argb) {
        try { ctx.fill(x1, y1, x2, y2, argb); } catch (Throwable ignored) {}
    }

    private static void outlineRect(GuiGraphics ctx, int x, int y, int w, int h, int argb) {
        try {
            ctx.fill(x,         y,         x + w,     y + 1,     argb);
            ctx.fill(x,         y + h - 1, x + w,     y + h,     argb);
            ctx.fill(x,         y,         x + 1,     y + h,     argb);
            ctx.fill(x + w - 1, y,         x + w,     y + h,     argb);
        } catch (Throwable ignored) {}
    }

    private void closeSafely() {
        Minecraft mc = this.client != null ? this.client : Minecraft.getInstance();
        if (mc != null) mc.setScreen(parent);
    }

    @Override
    public void close() { closeSafely(); }
}

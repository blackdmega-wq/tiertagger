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

import java.util.Optional;

/**
 * Standalone profile screen for {@code /tiertagger profile <name>}.
 *
 * Renders a centred opaque panel with:
 *  - a header showing the player skin (live for online players, fetched from
 *    mc-heads.net for offline players via {@link SkinFetcher}),
 *  - their highest tier across every service, and
 *  - one OuterTiers-style card per tier-list with the website's gamemode
 *    icons and per-mode tier labels.
 *
 * Defensive everywhere: any draw failure falls back to a coloured rectangle
 * so the panel is always legible even when data is half-loaded.
 *
 * <p>v1.7.10 fix: every {@code drawTextWithShadow} colour parameter must
 * include the high alpha byte ({@code 0xFF000000}) — MC 1.21.5+ treats
 * {@code alpha == 0} as "fully transparent" instead of the legacy "opaque",
 * which silently hid every label on this screen.
 */
public class TierProfileScreen extends Screen {

    private static final ResourceLocation STEVE = ResourceLocation.ofVanilla("textures/entity/player/wide/steve.png");

    // Layout constants
    private static final int PANEL_W_MAX = 480;
    private static final int CARD_GAP    = 6;
    private static final int CARD_PAD    = 8;
    private static final int ROW_H       = 14;
    private static final int ICON_SIZE   = 16;

    // Colours — every constant carries the 0xFF alpha byte so it can be passed
    // straight into drawTextWithShadow() / fill() without further masking.
    private static final int BG_PANEL        = 0xF20E1116;
    private static final int BG_PANEL_BORDER = 0xFF2A2F38;
    private static final int BG_HEADER       = 0xFF181C24;
    private static final int BG_CARD         = 0xFF15191F;
    private static final int BG_CARD_BAR     = 0xFF1E232C;
    private static final int FG_FAINT        = 0xFF9AA0AA;
    private static final int FG_TEXT         = 0xFFE6E8EC;
    private static final int FG_REGION       = 0xFFFFD27A;

    /** Force the high alpha byte on; needed for drawTextWithShadow() colour params on 1.21.5+. */
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
    private final String username;
    /** Optional filter — when non-null, only this service's card is rendered.
     *  Set by {@code /tiertagger search <name> [tierlist]}. */
    private final TierService onlyService;
    private boolean bgApplied = false;
    private int scrollY = 0;
    private int maxScroll = 0;

    public TierProfileScreen(Screen parent, String username) {
        this(parent, username, null);
    }

    public TierProfileScreen(Screen parent, String username, TierService onlyService) {
        super(Component.literal("TierTagger \u2014 Profile: " + (username == null ? "?" : username)
            + (onlyService != null ? "  (" + onlyService.shortLabel + " only)" : "")));
        this.parent      = parent;
        this.username    = username == null ? "" : username;
        this.onlyService = onlyService;
    }

    /** Set when init() throws, so render() can show a useful overlay instead of a blank screen. */
    private volatile String lastInitError = null;

    @Override
    protected void init() {
        lastInitError = null;
        try { TierTaggerCore.cache().peekData(username); } catch (Throwable ignored) {}
        // Kick off the offline-skin download so it shows up on subsequent frames.
        try { SkinFetcher.headFor(username); } catch (Throwable ignored) {}
        scrollY = 0;

        int panelW = Math.min(PANEL_W_MAX, this.width - 40);
        int panelX = (this.width - panelW) / 2;
        int btnY   = this.height - 28;

        // Always add the Close button FIRST and outside any sub-try so the user
        // can never get stuck on a screen they can't close, even if init throws.
        try {
            this.addDrawableChild(Button.builder(Component.literal("Close"), btn -> closeSafely())
                .dimensions(panelX + panelW - CARD_PAD - 80, btnY, 80, 20).build());
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] profile close button failed", t);
        }

        try {
            this.addDrawableChild(Button.builder(Component.literal("\u21BB Update"), btn -> {
                    try {
                        TierTaggerCore.cache().invalidatePlayer(username);
                        TierTaggerCore.cache().peekData(username);
                    } catch (Throwable ignored) {}
                })
                .dimensions(panelX + CARD_PAD, btnY, 80, 20).build());
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] profile update button failed", t);
            lastInitError = "Update button failed: " + t.getClass().getSimpleName();
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

            // (v1.21.11.55) Animated drop-shadow + breathing border.
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

            // Header sized to fit the FULL-body render returned by
            // mc-heads.net /body/ (~1:2.4 aspect). The user explicitly
            // asked for the chest and arms to be visible looking
            // diagonally to the side instead of just the head, so we
            // allocate a tall header and the body slot inside it is
            // portrait-shaped (≈ 1:2). drawHead() preserves the natural
            // image aspect inside the slot, so the body never stretches.
            int headerH = 110;
            renderHeader(ctx, panelX + CARD_PAD, panelTop + CARD_PAD,
                         panelW - CARD_PAD * 2, headerH);

            int bodyTop    = panelTop + CARD_PAD + headerH + 6;
            int bodyBottom = panelBottom - CARD_PAD;
            ctx.enableScissor(panelX + 1, bodyTop, panelX + panelW - 1, bodyBottom);
            int y = bodyTop - scrollY;
            y = renderCards(ctx, panelX + CARD_PAD, y, panelW - CARD_PAD * 2);
            ctx.disableScissor();
            maxScroll = Math.max(0, y + scrollY - bodyBottom);

            super.render(ctx, mouseX, mouseY, delta);

            if (lastInitError != null) {
                drawErrorOverlay(ctx, "Profile init failed", lastInitError);
            }
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] profile render", t);
            try { drawErrorOverlay(ctx, "Profile render failed",
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

    private void renderHeader(GuiGraphics ctx, int x, int y, int w, int h) {
        fillRect(ctx, x, y, x + w, y + h, BG_HEADER);

        // (v1.21.11.55) Animated diagonal sheen sweep across the header.
        // (v1.21.11.60) Slice CLIPPED to header rectangle so the wave starts
        // at the GUI's left edge and ends at its right edge.
        long t = System.currentTimeMillis() % 5000L;
        float sweep = t / 5000f;
        final int sliceW = 24;
        int sx = x + (int)(sweep * Math.max(0, w - sliceW));
        for (int i = 0; i < sliceW; i++) {
            int px = sx + i;
            if (px < x || px >= x + w) continue;
            int a = Math.max(0, 18 - Math.abs(i - sliceW / 2) * 2);
            fillRect(ctx, px, y, px + 1, y + h, withAlpha(0x00FFFFFF, a));
        }

        // Square skin slot — TRANSPARENT bottom-anchored box.
        int bodyH = h - 8;
        int bodyW = bodyH;             // square box
        int headX = x + 8;
        int headY = y + (h - bodyH) / 2;

        // (v1.21.11.55) Pulsing glow halo behind the player skin.
        float pulse = pulse01();
        int haloA = (int)(30 + 50 * pulse);
        for (int i = 6; i >= 1; i--) {
            int a = Math.max(0, haloA - i * 6);
            fillRect(ctx, headX - i, headY - i, headX + bodyW + i, headY + bodyH + i,
                     withAlpha(0x0090E1FF, a));
        }

        drawHead(ctx, username, headX, headY, bodyW, bodyH);

        int textX = headX + bodyW + 12;
        int textY = y + 10;
        ctx.drawTextWithShadow(this.textRenderer,
            Component.literal(username).formatted(ChatFormatting.WHITE, ChatFormatting.BOLD),
            textX, textY, 0xFFFFFFFF);

        Optional<PlayerData> opt = TierTaggerCore.cache().peekData(username);
        Ranking best = null;
        TierService bestSvc = null;
        if (opt.isPresent()) {
            for (TierService s : TierService.values()) {
                Ranking r = opt.get().get(s).highest();
                if (r == null) continue;
                if (best == null || r.score() > best.score()) { best = r; bestSvc = s; }
            }
        }
        String sub;
        int subColor;
        if (best != null) {
            sub = best.label() + " on " + bestSvc.shortLabel;
            subColor = TierTaggerCore.argbFor(best.label());
        } else if (opt.isEmpty()) {
            sub = "Loading\u2026"; subColor = FG_FAINT;
        } else {
            sub = "No tiers found"; subColor = FG_FAINT;
        }
        ctx.drawTextWithShadow(this.textRenderer,
            Component.literal(sub).withColor(rgb(subColor)),
            textX, textY + 14, opaque(subColor));

        // ── OuterTiers leaderboard rank (v1.21.11.58) ────────────────────
        // Peak tier was moved out of the header into the per-mode rows
        // (rendered next to each gamemode's current tier as "▲HT3") so
        // the header stays compact and the live preview slot below isn't
        // pushed off-screen. Only the global OT leaderboard rank stays
        // up here because it's a single number that doesn't fit anywhere
        // in the per-mode list.
        if (opt.isPresent()) {
            ServiceData otSd = opt.get().get(TierService.OUTERTIERS);
            if (otSd != null && otSd.overall > 0) {
                MutableComponent rankLine = Component.literal("OT rank: ").withColor(rgb(FG_FAINT))
                    .append(Component.literal("#" + otSd.overall)
                        .withColor(rgb(TierService.OUTERTIERS.accentArgb))
                        .copy().withStyle(ChatFormatting.BOLD));
                ctx.drawString(this.textRenderer, rankLine,
                    textX, textY + 28, FG_TEXT, true);
            }
        }

        if (opt.isPresent()) {
            int ranked = 0, loaded = 0;
            for (TierService s : TierService.values()) {
                ServiceData sd = opt.get().get(s);
                if (sd.fetchedAt > 0) loaded++;
                if (sd.rankedCount() > 0) ranked++;
            }
            String st = ranked + " / " + TierService.values().length + " ranked";
            if (loaded < TierService.values().length) st = "loading " + loaded + "/" + TierService.values().length;
            int sw = this.textRenderer.getWidth(st);
            ctx.drawTextWithShadow(this.textRenderer,
                Component.literal(st).withColor(rgb(FG_FAINT)),
                x + w - 10 - sw, textY + 6, FG_FAINT);
        }
    }

    // ── service cards ───────────────────────────────────────────────────────

    private int renderCards(GuiGraphics ctx, int x, int y, int w) {
        Optional<PlayerData> opt = TierTaggerCore.cache().peekData(username);
        if (opt.isEmpty()) {
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                Component.literal("Looking up player\u2026").formatted(ChatFormatting.GRAY),
                x + w / 2, y + 20, 0xFFAAAAAA);
            return y + 40;
        }
        PlayerData data = opt.get();

        for (TierService svc : TierService.values()) {
            // /tiertagger search <name> <tierlist> → only render that one card.
            if (onlyService != null && svc != onlyService) continue;
            ServiceData sd = data.get(svc);
            // Union of the service's known modes plus any modes the API
            // returned that we don't have in our static list — this is how
            // SubTiers' niche modes (creeper, manhunt, dia_smp, …) actually
            // show up after the user opens the profile.
            int extra = 0;
            int hidden = 0;
            for (String m : svc.modes) if (isModeHidden(svc, m)) hidden++;
            if (sd != null && sd.rankings != null) {
                for (String k : sd.rankings.keySet()) {
                    if (isModeHidden(svc, k)) continue;
                    if (!svc.modes.contains(k)) extra++;
                }
            }
            int rows = Math.max(1, svc.modes.size() - hidden + extra);
            int cardH = 22 + rows * ROW_H + 6;
            renderServiceCard(ctx, svc, sd, x, y, w, cardH);
            y += cardH + CARD_GAP;
        }
        return y;
    }

    /**
     * Per-service mode blacklist requested by the user: hide gamemodes that
     * either duplicate a mainstream mode or aren't actively maintained on
     * that tier list.
     *   MCTiers  — drop NethPot (keep NethOP)
     *   PvPTiers — drop NethOP AND Vanilla (Vanilla == Crystal on PvPTiers; only show Crystal)
     *   SubTiers — drop Dia 2v2
     */
    private static boolean isModeHidden(TierService svc, String mode) {
        if (mode == null) return false;
        // IMPORTANT: "nethpot" (Netherite Pot) and "nethop" (NethOP) are TWO
        // DISTINCT gamemodes. Do NOT lump them together.
        String m = mode.toLowerCase(java.util.Locale.ROOT);
        switch (svc) {
            case MCTIERS:
                // Hide only Netherite Pot. Keep NethOP and the regular Pot.
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

    private void renderServiceCard(GuiGraphics ctx, TierService svc, ServiceData sd,
                                   int x, int y, int w, int h) {
        int accent = svc.accentArgb;

        // (v1.21.11.55) Pulsing accent bloom on the left edge.
        fillRect(ctx, x, y, x + w, y + h, BG_CARD);
        outlineRect(ctx, x, y, w, h, 0xFF2A2F38);
        float pulse = pulse01();
        fillRect(ctx, x, y, x + 3, y + h, accent);
        for (int i = 0; i < 12; i++) {
            int a = Math.max(0, (int)((90 - i * 7) * (0.5f + 0.5f * pulse)));
            fillRect(ctx, x + 3 + i, y, x + 4 + i, y + h, withAlpha(accent, a));
        }
        fillRect(ctx, x + 3, y, x + w, y + 22, BG_CARD_BAR);

        // Title: "OT  OuterTiers"
        ctx.drawTextWithShadow(this.textRenderer,
            Component.literal(svc.shortLabel).withColor(rgb(accent)).copy().formatted(ChatFormatting.BOLD),
            x + 10, y + 7, opaque(accent));
        ctx.drawTextWithShadow(this.textRenderer,
            Component.literal(svc.displayName).formatted(ChatFormatting.WHITE),
            x + 10 + this.textRenderer.getWidth(svc.shortLabel) + 6, y + 7, FG_TEXT);

        // Right-hand header content:
        //   loading…                            (still fetching)
        //   not on this list                    (404 / no entry)
        //   [HT2]   EU · #137                   (ranked: best-tier pill + region/overall rank)
        int rightX = x + w - 10;
        if (sd.fetchedAt == 0L) {
            String s = "loading\u2026";
            int sw = this.textRenderer.getWidth(s);
            ctx.drawTextWithShadow(this.textRenderer,
                Component.literal(s).withColor(rgb(FG_FAINT)), rightX - sw, y + 7, FG_FAINT);
        } else if (sd.missing || sd.rankedCount() == 0) {
            String s = "not on this list";
            int sw = this.textRenderer.getWidth(s);
            ctx.drawTextWithShadow(this.textRenderer,
                Component.literal(s).withColor(rgb(FG_FAINT)), rightX - sw, y + 7, FG_FAINT);
        } else {
            String reg = (sd.region == null || sd.region.isBlank()) ? "" : sd.region;
            String tail = reg + (sd.overall > 0
                ? (reg.isEmpty() ? "#" + sd.overall : "  \u00B7  #" + sd.overall)
                : "");
            int tailW = tail.isEmpty() ? 0 : this.textRenderer.getWidth(tail);
            if (!tail.isEmpty()) {
                ctx.drawTextWithShadow(this.textRenderer,
                    Component.literal(tail).withColor(rgb(FG_REGION)), rightX - tailW, y + 7, FG_REGION);
            }
            // Best-tier pill, right of the title and left of the region/rank tail.
            Ranking best = sd.highest();
            if (best != null) {
                String pill = best.label();
                int pillTextW = this.textRenderer.getWidth(pill);
                int pillW = pillTextW + 8;
                int pillX = rightX - tailW - (tail.isEmpty() ? 0 : 8) - pillW;
                int color = TierTaggerCore.argbFor(pill);
                // Pill 15px tall (y+3..y+18) so the 8px-tall ASCII glyph at
                // y+8 sits visually centered (text-center y+11.5 == pill
                // center y+10.5, off by ½ px which rounds out cleanly).
                // Old layout was 14px tall with text at y+7 → text drifted
                // ~1.5 px above center, the bug the user spotted.
                // (v1.21.11.55) Animated halo behind the best-tier pill.
                int pillHaloA = (int)(80 + 80 * pulse);
                for (int i = 4; i >= 1; i--) {
                    int a = Math.max(0, pillHaloA - i * 18);
                    fillRect(ctx, pillX - i, y + 3 - i, pillX + pillW + i, y + 18 + i,
                             withAlpha(color, a));
                }
                fillRect(ctx, pillX, y + 3, pillX + pillW, y + 18, (color & 0x00FFFFFF) | 0x33000000);
                outlineRect(ctx, pillX, y + 3, pillW, 15, withAlpha(color, 200 + (int)(55 * pulse)));
                ctx.drawCenteredTextWithShadow(this.textRenderer,
                    Component.literal(pill).withColor(rgb(color)).copy().formatted(ChatFormatting.BOLD),
                    pillX + pillW / 2, y + 7, opaque(color));
            }
        }

        int rowY = y + 24;
        int innerX = x + 10;
        int innerW = w - 20;

        if (sd.fetchedAt == 0L) {
            ctx.drawTextWithShadow(this.textRenderer,
                Component.literal("Fetching " + svc.displayName + "\u2026").formatted(ChatFormatting.DARK_GRAY),
                innerX, rowY + 2, 0xFF808080);
            return;
        }

        // Render EVERY mode this service supports — ranked or not — so the
        // user always sees the full gamemode line-up with icons. Unranked
        // modes simply show a faint "—" on the right. We also include any
        // mode the API returned that's not in svc.modes (SubTiers regularly
        // surfaces extra niche modes), so nothing is silently dropped.
        java.util.LinkedHashSet<String> rowModes = new java.util.LinkedHashSet<>(svc.modes);
        if (sd.rankings != null) rowModes.addAll(sd.rankings.keySet());
        boolean alt = false;
        for (String mode : rowModes) {
            if (isModeHidden(svc, mode)) continue;
            Ranking r = sd.rankings.get(mode);
            renderModeRow(ctx, svc, mode, r, innerX, rowY, innerW, alt);
            rowY += ROW_H;
            alt = !alt;
        }
    }

    private void renderModeRow(GuiGraphics ctx, TierService svc, String mode, Ranking r,
                               int x, int y, int w, boolean alt) {
        if (alt) fillRect(ctx, x - 4, y, x + w + 4, y + ROW_H, 0x14FFFFFF);

        int textX = x;
        // 1) Try OuterTiers website-style PNG icon first. Pass the service id
        //    so per-service overrides (e.g. PvPTiers art for sword/uhc/pot/…)
        //    win over the shared MCTiers/OuterTiers set.
        ResourceLocation tex = ModeIcons.textureFor(svc == null ? null : svc.id, mode);
        boolean drewIcon = false;
        if (tex != null) {
            try {
                Compat.drawTexture(ctx, tex, x, y - 1, 0, 0, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
                drewIcon = true;
            } catch (Throwable ignored) {}
        }
        // 2) Fall back to the legacy item-icon mapping if no texture is bundled
        //    (or if the texture failed to render against an older MC mapping).
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

        // Mode name (slightly dimmed when player isn't ranked in this mode so
        // the eye is naturally drawn to the modes that do have a tier).
        boolean ranked = r != null && r.tierLevel > 0;
        String label = TierIcons.labelFor(mode);
        int labelCol = ranked ? FG_TEXT : FG_FAINT;
        ctx.drawTextWithShadow(this.textRenderer,
            Component.literal(label).withColor(rgb(labelCol)),
            textX, y + 3, labelCol);

        // Inline #position next to the mode name when known (e.g. "Vanilla #42").
        if (ranked && r.posInTier > 0) {
            String pos = "#" + r.posInTier;
            int posX = textX + this.textRenderer.getWidth(label) + 5;
            ctx.drawTextWithShadow(this.textRenderer,
                Component.literal(pos).formatted(ChatFormatting.DARK_GRAY),
                posX, y + 3, 0xFF707070);
        }

        // Right-hand side: tier badge, peak, retired marker, or em-dash if unranked.
        MutableComponent tier;
        if (!ranked) {
            tier = Component.literal("\u2014").formatted(ChatFormatting.DARK_GRAY);
        } else {
            String cur = r.label();
            int curColor = TierTaggerCore.argbFor(cur);
            tier = Component.literal(cur).withColor(rgb(curColor)).copy().formatted(ChatFormatting.BOLD);
            if (r.peakDiffers()) {
                String peak = r.peakLabel();
                int peakC = TierTaggerCore.argbFor(peak);
                tier = tier.append(Component.literal("  \u25B2").formatted(ChatFormatting.DARK_GRAY))
                           .append(Component.literal(peak).withColor(rgb(peakC)));
            }
            // Retired status now lives inline as the leading "R" in r.label()
            // (e.g. "RHT3"), so no extra " • ret" suffix is needed.
        }
        int tw = this.textRenderer.getWidth(tier);
        ctx.drawTextWithShadow(this.textRenderer, tier, x + w - tw, y + 3, 0xFFFFFFFF);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void drawHead(GuiGraphics ctx, String name, int x, int y, int boxW, int boxH) {
        // v1.21.11.39: render the TOP HALF of the skin (head + chest +
        // arms) cropped via UV math, scaled to fill the SQUARE slot,
        // anchored to the BOTTOM so the waist cut sits flush with the
        // bottom edge of the box (no gap). Matches the new compare
        // layout exactly.
        Optional<SkinFetcher.Skin> fetched = Optional.empty();
        try { fetched = SkinFetcher.skinFor(name); } catch (Throwable ignored) {}
        if (fetched.isPresent()) {
            try {
                SkinFetcher.Skin sk = fetched.get();
                int iw = Math.max(1, sk.width);
                int ih = Math.max(1, sk.height);
                int halfH = Math.max(1, ih / 2);
                double scale = Math.min((double) boxW / iw, (double) boxH / halfH);
                int dw = Math.max(1, (int) Math.floor(iw * scale));
                int dh = Math.max(1, (int) Math.floor(halfH * scale));
                int dx = x + (boxW - dw) / 2;
                int dy = y + (boxH - dh);   // anchor to bottom
                // textureHeight = 2*dh tells MC the texture is twice as
                // tall as the drawn rect, so we sample only the TOP HALF.
                Compat.drawTexture(ctx, sk.id, dx, dy, 0, 0, dw, dh, dw, dh * 2);
                return;
            } catch (Throwable ignored) {}
        }

        // Loading placeholder — head + chest + arms (top-half preview),
        // anchored to the bottom edge.
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

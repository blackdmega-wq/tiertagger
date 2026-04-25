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

    private static final Identifier STEVE = Identifier.ofVanilla("textures/entity/player/wide/steve.png");

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

    private final Screen parent;
    private final String username;
    private boolean bgApplied = false;
    private int scrollY = 0;
    private int maxScroll = 0;

    public TierProfileScreen(Screen parent, String username) {
        super(Text.literal("TierTagger \u2014 Profile: " + (username == null ? "?" : username)));
        this.parent   = parent;
        this.username = username == null ? "" : username;
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
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"), btn -> closeSafely())
                .dimensions(panelX + panelW - CARD_PAD - 80, btnY, 80, 20).build());
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] profile close button failed", t);
        }

        try {
            this.addDrawableChild(ButtonWidget.builder(Text.literal("\u21BB Update"), btn -> {
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

            int headerH = 56;
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

    private void renderHeader(DrawContext ctx, int x, int y, int w, int h) {
        fillRect(ctx, x, y, x + w, y + h, BG_HEADER);

        int headSize = h - 12;
        int headX = x + 8;
        int headY = y + (h - headSize) / 2;
        drawHead(ctx, username, headX, headY, headSize);

        int textX = headX + headSize + 12;
        int textY = y + 10;
        ctx.drawTextWithShadow(this.textRenderer,
            Text.literal(username).formatted(Formatting.WHITE, Formatting.BOLD),
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
            Text.literal(sub).withColor(rgb(subColor)),
            textX, textY + 14, opaque(subColor));

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
                Text.literal(st).withColor(rgb(FG_FAINT)),
                x + w - 10 - sw, textY + 6, FG_FAINT);
        }
    }

    // ── service cards ───────────────────────────────────────────────────────

    private int renderCards(DrawContext ctx, int x, int y, int w) {
        Optional<PlayerData> opt = TierTaggerCore.cache().peekData(username);
        if (opt.isEmpty()) {
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Looking up player\u2026").formatted(Formatting.GRAY),
                x + w / 2, y + 20, 0xFFAAAAAA);
            return y + 40;
        }
        PlayerData data = opt.get();

        for (TierService svc : TierService.values()) {
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
     *   MCTiers  — drop NethPot
     *   PvPTiers — drop NethPot AND Vanilla
     *   SubTiers — drop Dia 2v2
     */
    private static boolean isModeHidden(TierService svc, String mode) {
        if (mode == null) return false;
        String m = mode.toLowerCase(java.util.Locale.ROOT);
        switch (svc) {
            case MCTIERS:
                return m.equals("nethpot") || m.equals("nethop") || m.equals("neth_pot");
            case PVPTIERS:
                return m.equals("nethpot") || m.equals("nethop") || m.equals("neth_pot")
                    || m.equals("vanilla");
            case SUBTIERS:
                return m.equals("dia_2v2") || m.equals("dia2v2") || m.equals("2v2");
            default:
                return false;
        }
    }

    private void renderServiceCard(DrawContext ctx, TierService svc, ServiceData sd,
                                   int x, int y, int w, int h) {
        int accent = svc.accentArgb;

        // Card background with a subtle accent glow on the left edge.
        fillRect(ctx, x, y, x + w, y + h, BG_CARD);
        outlineRect(ctx, x, y, w, h, 0xFF2A2F38);
        fillRect(ctx, x, y, x + 3, y + h, accent);
        fillRect(ctx, x + 3, y, x + 6, y + h, (accent & 0x00FFFFFF) | 0x33000000);
        fillRect(ctx, x + 3, y, x + w, y + 22, BG_CARD_BAR);

        // Title: "OT  OuterTiers"
        ctx.drawTextWithShadow(this.textRenderer,
            Text.literal(svc.shortLabel).withColor(rgb(accent)).copy().formatted(Formatting.BOLD),
            x + 10, y + 7, opaque(accent));
        ctx.drawTextWithShadow(this.textRenderer,
            Text.literal(svc.displayName).formatted(Formatting.WHITE),
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
                Text.literal(s).withColor(rgb(FG_FAINT)), rightX - sw, y + 7, FG_FAINT);
        } else if (sd.missing || sd.rankedCount() == 0) {
            String s = "not on this list";
            int sw = this.textRenderer.getWidth(s);
            ctx.drawTextWithShadow(this.textRenderer,
                Text.literal(s).withColor(rgb(FG_FAINT)), rightX - sw, y + 7, FG_FAINT);
        } else {
            String reg = (sd.region == null || sd.region.isBlank()) ? "" : sd.region;
            String tail = reg + (sd.overall > 0
                ? (reg.isEmpty() ? "#" + sd.overall : "  \u00B7  #" + sd.overall)
                : "");
            int tailW = tail.isEmpty() ? 0 : this.textRenderer.getWidth(tail);
            if (!tail.isEmpty()) {
                ctx.drawTextWithShadow(this.textRenderer,
                    Text.literal(tail).withColor(rgb(FG_REGION)), rightX - tailW, y + 7, FG_REGION);
            }
            // Best-tier pill, right of the title and left of the region/rank tail.
            Ranking best = sd.highest();
            if (best != null) {
                String pill = best.label();
                int pillTextW = this.textRenderer.getWidth(pill);
                int pillW = pillTextW + 8;
                int pillX = rightX - tailW - (tail.isEmpty() ? 0 : 8) - pillW;
                int color = TierTaggerCore.argbFor(pill);
                fillRect(ctx, pillX, y + 4, pillX + pillW, y + 18, (color & 0x00FFFFFF) | 0x33000000);
                outlineRect(ctx, pillX, y + 4, pillW, 14, color);
                ctx.drawTextWithShadow(this.textRenderer,
                    Text.literal(pill).withColor(rgb(color)).copy().formatted(Formatting.BOLD),
                    pillX + 4, y + 7, opaque(color));
            }
        }

        int rowY = y + 24;
        int innerX = x + 10;
        int innerW = w - 20;

        if (sd.fetchedAt == 0L) {
            ctx.drawTextWithShadow(this.textRenderer,
                Text.literal("Fetching " + svc.displayName + "\u2026").formatted(Formatting.DARK_GRAY),
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
            renderModeRow(ctx, mode, r, innerX, rowY, innerW, alt);
            rowY += ROW_H;
            alt = !alt;
        }
    }

    private void renderModeRow(DrawContext ctx, String mode, Ranking r,
                               int x, int y, int w, boolean alt) {
        if (alt) fillRect(ctx, x - 4, y, x + w + 4, y + ROW_H, 0x14FFFFFF);

        int textX = x;
        // 1) Try OuterTiers website-style PNG icon first.
        Identifier tex = ModeIcons.textureFor(mode);
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

        // Mode name (slightly dimmed when player isn't ranked in this mode so
        // the eye is naturally drawn to the modes that do have a tier).
        boolean ranked = r != null && r.tierLevel > 0;
        String label = TierIcons.labelFor(mode);
        int labelCol = ranked ? FG_TEXT : FG_FAINT;
        ctx.drawTextWithShadow(this.textRenderer,
            Text.literal(label).withColor(rgb(labelCol)),
            textX, y + 3, labelCol);

        // Inline #position next to the mode name when known (e.g. "Vanilla #42").
        if (ranked && r.posInTier > 0) {
            String pos = "#" + r.posInTier;
            int posX = textX + this.textRenderer.getWidth(label) + 5;
            ctx.drawTextWithShadow(this.textRenderer,
                Text.literal(pos).formatted(Formatting.DARK_GRAY),
                posX, y + 3, 0xFF707070);
        }

        // Right-hand side: tier badge, peak, retired marker, or em-dash if unranked.
        MutableText tier;
        if (!ranked) {
            tier = Text.literal("\u2014").formatted(Formatting.DARK_GRAY);
        } else {
            String cur = r.label();
            int curColor = TierTaggerCore.argbFor(cur);
            tier = Text.literal(cur).withColor(rgb(curColor)).copy().formatted(Formatting.BOLD);
            if (r.peakDiffers()) {
                String peak = r.peakLabel();
                int peakC = TierTaggerCore.argbFor(peak);
                tier = tier.append(Text.literal("  \u25B2").formatted(Formatting.DARK_GRAY))
                           .append(Text.literal(peak).withColor(rgb(peakC)));
            }
            // Retired status now lives inline as the leading "R" in r.label()
            // (e.g. "RHT3"), so no extra " • ret" suffix is needed.
        }
        int tw = this.textRenderer.getWidth(tier);
        ctx.drawTextWithShadow(this.textRenderer, tier, x + w - tw, y + 3, 0xFFFFFFFF);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void drawHead(DrawContext ctx, String name, int x, int y, int size) {
        // Border / shadow plate
        ctx.fill(x - 2, y - 2, x + size + 2, y + size + 2, 0xFF1A1A1A);

        // 1) Live skin from the local player tab list (online only). Resolved
        // reflectively so this source compiles whether SkinTextures lives in
        // {@code net.minecraft.client.util} (1.21.1-1.21.4) or
        // {@code net.minecraft.entity.player} (1.21.5+).
        Object st = null;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.getNetworkHandler() != null) {
                PlayerListEntry e = mc.getNetworkHandler().getPlayerListEntry(name);
                if (e != null) {
                    try {
                        st = PlayerListEntry.class.getMethod("getSkinTextures").invoke(e);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        if (st != null) {
            try {
                Compat.drawPlayerFace(ctx, st, STEVE, x, y, size);
                return;
            } catch (Throwable ignored) {}
        }

        // 2) Fetched offline avatar (mc-heads.net).
        Optional<Identifier> fetched = Optional.empty();
        try { fetched = SkinFetcher.headFor(name); } catch (Throwable ignored) {}
        if (fetched.isPresent()) {
            try {
                Compat.drawTexture(ctx, fetched.get(), x, y, 0, 0, size, size, size, size);
                return;
            } catch (Throwable ignored) {}
        }

        // 3) Last-resort Steve fallback so we never render an empty box.
        try {
            Compat.drawPlayerFace(ctx, null, STEVE, x, y, size);
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

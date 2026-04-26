package com.outertiers.tiertagger.fabric.screen;

import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierService;
import com.outertiers.tiertagger.common.TierTaggerCore;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * TierTagger settings screen.
 *
 * <p>v1.21.11.29 redesign: 3-tab layout matching the user-supplied mock-ups.
 * The tabs are
 * <ul>
 *   <li><b>Settings</b> — every existing toggle (badge services, modes,
 *       appearance, mode filters, …).</li>
 *   <li><b>Tier Colors</b> — one editable hex row per tier
 *       (HT1, LT1, …, Retired) with a live colour swatch. Defaults seed from
 *       {@link TierConfig#defaultTierColors()} which is the user's current
 *       palette baked into {@link TierTaggerCore#argbFor(String)}.</li>
 *   <li><b>Tierlists</b> — one selectable row per {@link TierService}
 *       (MCTiers, OuterTiers, PvPTiers, SubTiers); the chosen one is the
 *       primary service and is suffixed with {@code "(selected)"}.</li>
 * </ul>
 *
 * <p>Each tab keeps its own scroll position and re-renders independently. The
 * scrollable body is rendered OUTSIDE a scissor (so the bottom action row is
 * never clipped); the title strip and tab strip are repainted on top after
 * {@code super.render()} to cover any widget that briefly leaks.
 */
public class TierConfigScreen extends Screen {

    private static final int BTN_W   = 150;
    private static final int BTN_H   = 20;
    private static final int BTN_GAP = 6;
    private static final int ROW_H   = BTN_H + 4;

    private static final int PANEL_W_MAX     = 380;
    private static final int BG_PANEL        = 0xF20E1116;
    private static final int BG_PANEL_BORDER = 0xFF2A2F38;
    private static final int BG_HEADER       = 0xFF181C24;
    /** Subtle gradient stop for the title strip (top → bottom blend). */
    private static final int BG_HEADER_TOP   = 0xFF20283A;
    private static final int BG_HEADER_BOT   = 0xFF12161E;
    private static final int BG_TAB_INACTIVE = 0xFF1F232C;
    private static final int BG_TAB_ACTIVE   = 0xFF0E1116;
    private static final int FG_FAINT        = 0xFF9AA0AA;
    private static final int FG_SECTION      = 0xFFFFAA00;
    /** Yellow accent used for the active-tab underline and title divider. */
    private static final int ACCENT          = 0xFFFFC857;
    private static final int ACCENT_SOFT     = 0x66FFC857;

    /** Bigger, more breathable tabs so the header reads as a real toolbar. */
    private static final int TAB_H           = 26;
    private static final int TAB_STRIP_TOP   = 34;
    /** Visible space between adjacent tabs — user asked for more breathing room. */
    private static final int TAB_GAP         = 10;
    private static final String[] TAB_LABELS = { "Settings", "Tier Colors", "Tiers Config" };

    private final Screen parent;
    private boolean bgApplied = false;

    private final List<int[]>  sectionHeaders     = new ArrayList<>();
    private final List<String> sectionHeadersText = new ArrayList<>();

    /** Tab-strip hit boxes — populated each render() so mouseClicked can use them. */
    private final int[][] tabBounds = new int[TAB_LABELS.length][4];

    /** 0 = Settings, 1 = Tier Colors, 2 = Tierlists. */
    private int currentTab = 0;
    /** Per-tab scroll positions so switching tabs preserves where you were. */
    private final int[] scrollByTab = new int[TAB_LABELS.length];

    private int scrollY   = 0;
    private int maxScroll = 0;
    private int bodyTop   = TAB_STRIP_TOP + TAB_H + 4;
    private int bodyBottom = 0;
    private int maxRowUsed = 0;

    public TierConfigScreen(Screen parent) {
        super(Text.literal("TierTagger \u2014 Settings"));
        this.parent = parent;
    }

    private int colX(int col) {
        int totalW = BTN_W * 2 + BTN_GAP;
        int startX = (this.width - totalW) / 2;
        return startX + col * (BTN_W + BTN_GAP);
    }

    /** Single full-row x/width — used by Tier Colors and Tierlists tabs. */
    private int rowX() { return colX(0); }
    private int rowW() { return BTN_W * 2 + BTN_GAP; }

    private int rowY(int row) {
        if (row > maxRowUsed) maxRowUsed = row;
        return bodyTop + 6 + row * ROW_H - scrollY;
    }

    private static int rgb(int argb) { return argb & 0xFFFFFF; }

    private volatile String lastInitError = null;

    @Override
    protected void init() {
        lastInitError = null;
        sectionHeaders.clear();
        sectionHeadersText.clear();
        maxRowUsed = 0;
        bodyTop    = TAB_STRIP_TOP + TAB_H + 4;
        bodyBottom = this.height - 32 - ROW_H;
        scrollY    = scrollByTab[currentTab];
        try { buildWidgets(); }
        catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] config screen init failed", t);
            lastInitError = t.getClass().getSimpleName() + ": " +
                (t.getMessage() == null ? "(no message)" : t.getMessage());
            this.clearChildren();
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Done"), b -> closeSafely())
                .dimensions(this.width / 2 - 110, this.height - 27, 220, BTN_H).build());
        }
    }

    private void switchTab(int newTab) {
        if (newTab < 0 || newTab >= TAB_LABELS.length || newTab == currentTab) return;
        scrollByTab[currentTab] = scrollY;
        currentTab = newTab;
        scrollY = scrollByTab[currentTab];
        this.clearChildren();
        try { buildWidgets(); } catch (Throwable ignored) {}
    }

    /**
     * Builds the three tab-switch buttons at the top of the panel. They use
     * {@link ButtonWidget} so we don't need to override {@code mouseClicked}
     * (whose signature changes between MC versions). The custom tab strip
     * background is rendered separately in {@link #render} on top of the panel
     * but BEHIND these buttons so the labels stay clickable.
     */
    private void addTabButtons() {
        int totalW = BTN_W * 2 + BTN_GAP;
        int panelW = Math.min(PANEL_W_MAX, this.width - 24);
        panelW = Math.max(panelW, totalW + 24);
        int panelX = (this.width - panelW) / 2;
        int innerLeft  = panelX + 8;
        int innerRight = panelX + panelW - 8;
        int innerW     = innerRight - innerLeft;
        int gap        = TAB_GAP;
        int tabW       = (innerW - gap * (TAB_LABELS.length - 1)) / TAB_LABELS.length;

        for (int i = 0; i < TAB_LABELS.length; i++) {
            final int idx = i;
            int tx = innerLeft + i * (tabW + gap);
            // Plain labels — the active state is already shown by the
            // bottom accent bar drawn in render() and by Minecraft's
            // own button hover/press visuals, so the » «  decoration
            // looked redundant and crowded the label.
            ButtonWidget btn = ButtonWidget.builder(
                    Text.literal(TAB_LABELS[i]),
                    b -> switchTab(idx))
                .dimensions(tx, TAB_STRIP_TOP, tabW, TAB_H)
                .build();
            this.addDrawableChild(btn);
            tabBounds[i][0] = tx;
            tabBounds[i][1] = TAB_STRIP_TOP;
            tabBounds[i][2] = tabW;
            tabBounds[i][3] = TAB_H;
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hd, double vd) {
        int prev = scrollY;
        scrollY = Math.max(0, Math.min(maxScroll, scrollY - (int)(vd * 16)));
        if (scrollY != prev) {
            scrollByTab[currentTab] = scrollY;
            this.clearChildren();
            try { buildWidgets(); } catch (Throwable ignored) {}
        }
        return true;
    }

    private void safeAdd(String label, Runnable build) {
        try { build.run(); }
        catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] config widget " + label + " failed", t);
        }
    }

    /**
     * Attach a hover tooltip to the most recently added widget (or any widget
     * passed in). Tooltips render with Minecraft's vanilla purple-bordered
     * panel — the same look the user requested in the screenshots — and only
     * appear when the cursor lingers over the widget, so they stay out of the
     * way during normal interaction.
     *
     * <p>Defensive: a null widget or a missing {@code setTooltip} method on
     * older mappings is silently ignored so the screen still builds.
     */
    private static void tip(ClickableWidget w, String text) {
        if (w == null || text == null || text.isEmpty()) return;
        try { w.setTooltip(Tooltip.of(Text.literal(text))); }
        catch (Throwable ignored) {}
    }

    /** Helper: add a widget AND attach a tooltip in one call. */
    private <T extends ClickableWidget> T addTipped(T w, String tooltip) {
        try { this.addDrawableChild(w); } catch (Throwable ignored) {}
        tip(w, tooltip);
        return w;
    }

    private void addSectionHeader(int row, String text) {
        sectionHeaders.add(new int[]{ rowY(row) - 2, 0 });
        sectionHeadersText.add(text);
    }

    private void buildWidgets() {
        sectionHeaders.clear();
        sectionHeadersText.clear();
        maxRowUsed = 0;

        TierConfig cfg = TierTaggerCore.config();
        if (cfg == null) {
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> closeSafely())
                .dimensions(this.width / 2 - 75, this.height / 2, 150, BTN_H).build());
            return;
        }

        // Tab strip first so the tab buttons sit at a stable z-index above
        // the rest of the body widgets.
        addTabButtons();

        switch (currentTab) {
            case 1:  buildTierColorsTab(cfg); break;
            case 2:  buildTiersConfigTab(cfg); break;
            default: buildSettingsTab(cfg);   break;
        }

        // ── Bottom action bar (anchored, never scrolls) ────────────────────
        int bottomY = this.height - 27;
        if (currentTab == 0) {
            // Settings keeps its existing Refresh Cache + Done bar.
            safeAdd("refreshCache", () -> this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Refresh Cache"),
                    b -> { try { TierTaggerCore.cache().invalidate(); } catch (Throwable ignored) {} })
                .dimensions(colX(0), bottomY, BTN_W, BTN_H).build()));
            safeAdd("done", () -> this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Done"), b -> closeSafely())
                .dimensions(colX(1), bottomY, BTN_W, BTN_H).build()));
        } else {
            // Tier Colors / Tierlists — Reset to Default + Done, like the mock-ups.
            final int targetTab = currentTab;
            safeAdd("reset", () -> this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Reset to Default"),
                    b -> {
                        try {
                            if (targetTab == 1) {
                                cfg.tierColors = null;
                            } else if (targetTab == 2) {
                                // Tiers Config: reset every badge-assignment field
                                // (primary + left + right) so the user gets a
                                // clean slate that still satisfies left ≠ right.
                                cfg.primaryService = TierService.OUTERTIERS.id;
                                cfg.leftService    = TierService.OUTERTIERS.id;
                                cfg.rightService   = TierService.MCTIERS.id;
                            }
                            cfg.save();
                            try { TierTaggerCore.cache().invalidate(); } catch (Throwable ignored) {}
                        } catch (Throwable ignored) {}
                        this.clearChildren();
                        try { buildWidgets(); } catch (Throwable ignored) {}
                    })
                .dimensions(colX(0), bottomY, BTN_W, BTN_H).build()));
            safeAdd("done", () -> this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Done"), b -> closeSafely())
                .dimensions(colX(1), bottomY, BTN_W, BTN_H).build()));
        }

        int contentH = (maxRowUsed + 2) * ROW_H;
        int viewH    = bodyBottom - bodyTop;
        maxScroll = Math.max(0, contentH - viewH);
        if (scrollY > maxScroll) scrollY = maxScroll;
        scrollByTab[currentTab] = scrollY;

        // Hide & disable any scrollable widget whose y position has scrolled
        // outside the visible body area. Without this, scrolled-up widgets
        // (e.g. "Tab Modes…") would render ON TOP of the fixed bottom action
        // bar (Refresh Cache / Done), absorb their clicks, and visually
        // overlap the tab strip — exactly the z-index issue the user
        // reported in the v1.21.11.34 feedback.
        clipScrollableWidgets();
    }

    /**
     * Mark widgets that scrolled out of the body area as non-visible /
     * non-active. The two pinned regions — the tab strip at
     * {@code y == TAB_STRIP_TOP} and the bottom action bar at
     * {@code y == this.height - 27} — are exempt so their buttons keep
     * working at all scroll positions.
     */
    private void clipScrollableWidgets() {
        int bottomY = this.height - 27;
        for (net.minecraft.client.gui.Element el : this.children()) {
            if (!(el instanceof ClickableWidget cw)) continue;
            int wy = cw.getY();
            if (wy == TAB_STRIP_TOP) continue;        // tab strip — always live
            if (wy == bottomY)        continue;        // bottom action bar — always live
            int wh = cw.getHeight();
            // Strict containment: widget must fit fully within the body
            // viewport. Partial widgets at the edges get hidden so they
            // never bleed into the tab strip or the bottom action bar.
            boolean inBody = wy >= bodyTop - 1 && (wy + wh) <= bodyBottom + 1;
            cw.visible = inBody;
            cw.active  = inBody;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Tab 0: Settings (legacy content, every toggle the user already had)
    // ─────────────────────────────────────────────────────────────────────
    private void buildSettingsTab(TierConfig cfg) {
        final int[] rRef = { 0 };

        // Badge Services moved to the dedicated "Tiers Config" tab — the
        // user wanted left/right badge selection laid out as a service list
        // there with a left/right arrow picker per service.
        addSectionHeader(rRef[0], "\u2014 General \u2014");
        rRef[0]++;
        safeAdd("primaryService", () -> {
            ClickableWidget w = CyclingButtonWidget.<TierService>builder(
                    s -> Text.literal(s.displayName).withColor(rgb(s.accentArgb)),
                    cfg.primaryServiceEnum())
                .values(TierService.values())
                .build(colX(0), rowY(rRef[0]), BTN_W, BTN_H,
                    Text.literal("Primary Service"),
                    (b, v) -> { cfg.primaryService = v.id; cfg.save(); });
            addTipped(w, "Which tierlist drives the single-tier helper " +
                "(used by /tiertagger and the active service display).");
        });
        safeAdd("displayMode", () -> {
            List<String> modes = new ArrayList<>();
            modes.add("highest");
            modes.addAll(TierService.allKnownModes());
            String initial = cfg.displayMode == null ? "highest" : cfg.displayMode.toLowerCase();
            if (!modes.contains(initial)) modes.add(initial);
            ClickableWidget w = CyclingButtonWidget.<String>builder(s -> Text.literal(prettyMode(s)), initial)
                    .values(modes)
                    .build(colX(1), rowY(rRef[0]), BTN_W, BTN_H,
                        Text.literal("Display Mode"),
                        (b, v) -> { cfg.displayMode = v; cfg.save(); });
            addTipped(w, "Choose which gamemode tier is shown by the primary service. " +
                "'Highest' picks whichever mode the player ranks highest in.");
        });
        rRef[0]++;

        // Per-side gamemode picker.
        safeAdd("leftMode", () -> {
            TierService leftSvc = cfg.leftServiceEnum();
            List<String> modes = new ArrayList<>();
            modes.add("highest");
            for (String m : leftSvc.modes) if (!modes.contains(m)) modes.add(m);
            String initial = cfg.leftMode == null ? "highest" : cfg.leftMode.toLowerCase();
            if (!modes.contains(initial)) modes.add(initial);
            ClickableWidget w = CyclingButtonWidget.<String>builder(s -> Text.literal(prettyMode(s)), initial)
                    .values(modes)
                    .build(colX(0), rowY(rRef[0]), BTN_W, BTN_H,
                        Text.literal("Left Mode"),
                        (b, v) -> { cfg.leftMode = v; cfg.save(); });
            addTipped(w, "Which gamemode the LEFT badge reads its tier from.");
        });
        safeAdd("rightMode", () -> {
            TierService rightSvc = cfg.rightServiceEnum();
            List<String> modes = new ArrayList<>();
            modes.add("highest");
            for (String m : rightSvc.modes) if (!modes.contains(m)) modes.add(m);
            String initial = cfg.rightMode == null ? "highest" : cfg.rightMode.toLowerCase();
            if (!modes.contains(initial)) modes.add(initial);
            ClickableWidget w = CyclingButtonWidget.<String>builder(s -> Text.literal(prettyMode(s)), initial)
                    .values(modes)
                    .build(colX(1), rowY(rRef[0]), BTN_W, BTN_H,
                        Text.literal("Right Mode"),
                        (b, v) -> { cfg.rightMode = v; cfg.save(); });
            addTipped(w, "Which gamemode the RIGHT badge reads its tier from.");
        });
        rRef[0]++;

        addSectionHeader(rRef[0], "\u2014 Where to Show \u2014");
        rRef[0]++;
        safeAdd("showInTab", () -> {
            ClickableWidget w = CyclingButtonWidget.onOffBuilder(cfg.showInTab)
                .build(colX(0), rowY(rRef[0]), BTN_W, BTN_H, Text.literal("Tab Badges"),
                    (b, v) -> { cfg.showInTab = v; cfg.save(); });
            addTipped(w, "Show tier badges next to player names in the tab list (TAB key).");
        });
        safeAdd("showNametag", () -> {
            ClickableWidget w = CyclingButtonWidget.onOffBuilder(cfg.showNametag)
                .build(colX(1), rowY(rRef[0]), BTN_W, BTN_H, Text.literal("Nametag (F5)"),
                    (b, v) -> { cfg.showNametag = v; cfg.save(); });
            addTipped(w, "Show tier badges above players' heads in the world (third-person view).");
        });
        rRef[0]++;

        safeAdd("rightBadgeEnabled", () -> {
            ClickableWidget w = CyclingButtonWidget.onOffBuilder(cfg.rightBadgeEnabled)
                .build(colX(0), rowY(rRef[0]), BTN_W, BTN_H, Text.literal("Dual Badges"),
                    (b, v) -> { cfg.rightBadgeEnabled = v; cfg.save(); });
            addTipped(w, "Show two tier badges (one for each side's tierlist). When OFF, only the LEFT badge is shown.");
        });
        safeAdd("showPeak", () -> {
            ClickableWidget w = CyclingButtonWidget.onOffBuilder(cfg.showPeak)
                .build(colX(1), rowY(rRef[0]), BTN_W, BTN_H, Text.literal("Use Peak Tier"),
                    (b, v) -> { cfg.showPeak = v; cfg.save(); });
            addTipped(w, "Show each player's all-time PEAK tier instead of their current tier.");
        });
        rRef[0]++;

        addSectionHeader(rRef[0], "\u2014 Appearance \u2014");
        rRef[0]++;
        safeAdd("coloredBadges", () -> {
            ClickableWidget w = CyclingButtonWidget.onOffBuilder(cfg.coloredBadges)
                .build(colX(0), rowY(rRef[0]), BTN_W, BTN_H, Text.literal("Coloured Badges"),
                    (b, v) -> { cfg.coloredBadges = v; cfg.save(); });
            addTipped(w, "Colour the tier text (HT1 = gold, HT2 = silver, …). When OFF, badges are plain white.");
        });
        safeAdd("badgeFormat", () -> {
            List<String> formats = Arrays.asList(TierConfig.BADGE_FORMATS);
            String initialFormat = (cfg.badgeFormat == null || !formats.contains(cfg.badgeFormat))
                ? "bracket" : cfg.badgeFormat;
            ClickableWidget w = CyclingButtonWidget.<String>builder(Text::literal, initialFormat)
                    .values(formats)
                    .build(colX(1), rowY(rRef[0]), BTN_W, BTN_H,
                        Text.literal("Badge Format"),
                        (b, v) -> { cfg.badgeFormat = v; cfg.save(); });
            addTipped(w, "How the tier label is wrapped: bracket = [HT1], plain = HT1, short = HT1.");
        });
        rRef[0]++;

        safeAdd("showServiceIcon", () -> {
            ClickableWidget w = CyclingButtonWidget.onOffBuilder(cfg.showServiceIcon)
                .build(colX(0), rowY(rRef[0]), BTN_W, BTN_H, Text.literal("Service Tag"),
                    (b, v) -> { cfg.showServiceIcon = v; cfg.save(); });
            addTipped(w, "Prefix each badge with the tierlist's short name (OT, MC, PVP, SUB).");
        });
        safeAdd("modeIcons", () -> {
            ClickableWidget w = CyclingButtonWidget.onOffBuilder(!cfg.disableIcons && cfg.showModeIcon)
                .build(colX(1), rowY(rRef[0]), BTN_W, BTN_H, Text.literal("Gamemode Icons"),
                    (b, v) -> { cfg.disableIcons = !v; cfg.showModeIcon = v; cfg.save(); });
            addTipped(w, "Show the gamemode icon (sword, axe, …) next to each tier on the profile screens.");
        });
        rRef[0]++;

        safeAdd("disableTiers", () -> {
            ClickableWidget w = CyclingButtonWidget.onOffBuilder(!cfg.disableTiers)
                .build(colX(0), rowY(rRef[0]), BTN_W, BTN_H, Text.literal("Show Tier Text"),
                    (b, v) -> { cfg.disableTiers = !v; cfg.save(); });
            addTipped(w, "Master switch — when OFF, no tier badges appear anywhere.");
        });
        safeAdd("disableAnimations", () -> {
            ClickableWidget w = CyclingButtonWidget.onOffBuilder(!cfg.disableAnimations)
                .build(colX(1), rowY(rRef[0]), BTN_W, BTN_H, Text.literal("Animations"),
                    (b, v) -> { cfg.disableAnimations = !v; cfg.save(); });
            addTipped(w, "Enable subtle fade / pulse animations on the badges.");
        });
        rRef[0]++;

        safeAdd("fallthrough", () -> {
            ClickableWidget w = CyclingButtonWidget.onOffBuilder(cfg.fallthroughToHighest)
                .build(colX(0), rowY(rRef[0]), BTN_W, BTN_H, Text.literal("Fallback to Highest"),
                    (b, v) -> { cfg.fallthroughToHighest = v; cfg.save(); });
            addTipped(w, "If a player has no tier in the selected gamemode, fall back to their highest tier.");
        });
        safeAdd("ttl", () -> {
            List<Integer> ttls = Arrays.asList(60, 180, 300, 600, 1800, 3600);
            int initial = ttls.contains(cfg.cacheTtlSeconds) ? cfg.cacheTtlSeconds : 300;
            ClickableWidget w = CyclingButtonWidget.<Integer>builder(s -> Text.literal(prettyTtl(s)), initial)
                    .values(ttls)
                    .build(colX(1), rowY(rRef[0]), BTN_W, BTN_H,
                        Text.literal("Cache TTL"),
                        (b, v) -> { cfg.cacheTtlSeconds = v; cfg.save(); });
            addTipped(w, "How long a player's tier data is cached locally before being re-fetched.");
        });
        rRef[0]++;

        addSectionHeader(rRef[0], "\u2014 Enabled Services \u2014");
        rRef[0]++;
        TierService[] svcs = TierService.values();
        for (int i = 0; i < svcs.length; i++) {
            final TierService svc = svcs[i];
            final int col = i % 2;
            if (col == 0 && i > 0) rRef[0]++;
            safeAdd("svc:" + svc.id, () -> {
                ClickableWidget w = CyclingButtonWidget.onOffBuilder(cfg.isServiceEnabled(svc))
                    .build(colX(col), rowY(rRef[0]), BTN_W, BTN_H,
                        Text.literal(svc.displayName).withColor(rgb(svc.accentArgb)),
                        (b, v) -> {
                            cfg.setServiceEnabled(svc, v);
                            cfg.save();
                            try { TierTaggerCore.cache().invalidate(); } catch (Throwable ignored) {}
                        });
                addTipped(w, "Fetch tier data from " + svc.displayName + ". Disable to skip this tierlist entirely.");
            });
        }
        rRef[0]++;

        addSectionHeader(rRef[0], "\u2014 Mode Filters \u2014");
        rRef[0]++;
        safeAdd("tabModes", () -> {
            ClickableWidget w = ButtonWidget.builder(
                    Text.literal("Tab Modes\u2026"),
                    b -> {
                        MinecraftClient mc = this.client != null ? this.client : MinecraftClient.getInstance();
                        if (mc != null) mc.setScreen(new ModeFilterScreen(this, true));
                    })
                .dimensions(colX(0), rowY(rRef[0]), BTN_W, BTN_H).build();
            addTipped(w, "Pick which gamemodes contribute to the badge shown in the tab list.");
        });
        safeAdd("nametagModes", () -> {
            ClickableWidget w = ButtonWidget.builder(
                    Text.literal("Nametag Modes\u2026"),
                    b -> {
                        MinecraftClient mc = this.client != null ? this.client : MinecraftClient.getInstance();
                        if (mc != null) mc.setScreen(new ModeFilterScreen(this, false));
                    })
                .dimensions(colX(1), rowY(rRef[0]), BTN_W, BTN_H).build();
            addTipped(w, "Pick which gamemodes contribute to the badge shown above player heads.");
        });
        rRef[0]++;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Tab 1: Tier Colors — one editable hex row per tier with a swatch
    // ─────────────────────────────────────────────────────────────────────
    /** Row metadata for the Tier Colors tab so render() can paint label + swatch over each row. */
    private static final class ColorRow {
        final String tierKey;
        final TextFieldWidget field;
        ColorRow(String t, TextFieldWidget f) { this.tierKey = t; this.field = f; }
    }
    private final List<ColorRow> colorRows = new ArrayList<>();

    private void buildTierColorsTab(TierConfig cfg) {
        colorRows.clear();
        final int[] rRef = { 0 };
        final int swatchW = 22;
        final int swatchGap = 6;
        final int fieldW = rowW() - swatchW - swatchGap;

        for (final String key : TierConfig.TIER_KEYS) {
            final int row = rRef[0]++;
            safeAdd("color:" + key, () -> {
                TextFieldWidget f = new TextFieldWidget(
                    this.textRenderer,
                    rowX(), rowY(row), fieldW, BTN_H,
                    Text.literal(key));
                f.setMaxLength(7);
                f.setText(cfg.getTierColorHex(key));
                f.setChangedListener(s -> {
                    try {
                        cfg.setTierColorHex(key, s);
                        cfg.save();
                    } catch (Throwable t) {
                        TierTaggerCore.LOGGER.warn("[TierTagger] tier-colour save failed for {}", key, t);
                    }
                });
                this.addDrawableChild(f);
                colorRows.add(new ColorRow(key, f));
            });
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Tab 2: "Tiers Config" — v1.21.11.34 redesign per user request.
    //
    // Layout (all main toggles full-width, single column):
    //   Row 1 : [ Disable Tiers : ON/OFF ]
    //   Row 2 : [ Enable Dynamic Separator : ON/OFF ]
    //   Row 3 : [ Displayed Tiers : Selected | Highest | Adaptive Highest ]
    //   Row 4 : [ Enable auto kit detect : ON/OFF ]
    //   Row 5 : [ ↻ Reload ] [ ◎ Cycle ] [ ⚒ Mode ] [ ≡ Tablist ] [ ✎ Chat ]
    //           — five evenly-spaced BIG icon buttons (taller than a normal
    //             row) so the active actions read clearly. Reload + Cycle
    //             are the same hotkey shortcuts that used to live on row 4.
    //             G/T/C migrate down here from the old "row 1" cluster, as
    //             the user asked, so the main Disable Tiers toggle gets
    //             the full panel width.
    //   --- spacer ---
    //   Row 6 : [ Advanced Settings ]
    //
    // What got removed (per user request):
    //   - Big "Active service / click to cycle" logo button in the middle.
    //   - The [←] [•] [→] display-position picker beneath the logo.
    //   The same routing power is still available via "Advanced Settings".
    // ─────────────────────────────────────────────────────────────────────
    private void buildTiersConfigTab(TierConfig cfg) {
        if (advancedRouting) {
            buildAdvancedRoutingView(cfg);
            return;
        }
        final int[] rRef = { 0 };

        // ── Row 1: Disable Tiers (full width) ─────────────────────────────
        final int row1y = rowY(rRef[0]);
        safeAdd("disableTiers", () -> {
            ClickableWidget w = CyclingButtonWidget
                .onOffBuilder(!cfg.disableTiers)
                .build(rowX(), row1y, rowW(), BTN_H,
                    Text.literal("Disable Tiers"),
                    (b, v) -> { cfg.disableTiers = !v; cfg.save(); });
            addTipped(w, "Toggle the entire tier display. When OFF, no tier badges appear anywhere.");
        });
        rRef[0]++;

        // ── Row 2: Enable Dynamic Separator (full width) ──────────────────
        final int row2y = rowY(rRef[0]);
        safeAdd("dynamicSeparator", () -> {
            ClickableWidget w = CyclingButtonWidget
                .onOffBuilder(cfg.dynamicSeparator)
                .build(rowX(), row2y, rowW(), BTN_H,
                    Text.literal("Enable Dynamic Separator"),
                    (b, v) -> { cfg.dynamicSeparator = v; cfg.save(); });
            addTipped(w, "Make the Tiers separator color match the tier color.");
        });
        rRef[0]++;

        // ── Row 3: Displayed Tiers (cycling Selected/Highest/Adaptive) ────
        final int row3y = rowY(rRef[0]);
        safeAdd("displayedTiers", () -> {
            String initial = cfg.displayedTiers == null ? "adaptive_highest" : cfg.displayedTiers;
            ClickableWidget w = CyclingButtonWidget
                .<String>builder(s -> Text.literal(TierConfig.displayedTiersLabel(s)), initial)
                .values(Arrays.asList(TierConfig.DISPLAYED_TIER_MODES))
                .build(rowX(), row3y, rowW(), BTN_H,
                    Text.literal("Displayed Tiers"),
                    (b, v) -> { cfg.displayedTiers = v; cfg.save(); });
            addTipped(w,
                "Selected: only the selected tier will be displayed.\n" +
                "Highest: only the player's highest tier will be displayed.\n" +
                "Adaptive Highest: the highest tier will be shown if the selected one does not exist.");
        });
        rRef[0]++;

        // ── Row 4: Enable auto kit detect (full width) ────────────────────
        final int row4y = rowY(rRef[0]);
        safeAdd("autoKitDetect", () -> {
            ClickableWidget w = CyclingButtonWidget
                .onOffBuilder(cfg.autoKitDetect)
                .build(rowX(), row4y, rowW(), BTN_H,
                    Text.literal("Enable auto kit detect"),
                    (b, v) -> { cfg.autoKitDetect = v; cfg.save(); });
            addTipped(w,
                "Tiers will always scan your inventory to display the right gamemode " +
                "(instead of having to press 'Z' / 'I').");
        });
        rRef[0]++;

        // ── Row 5: BIG action-icon row ────────────────────────────────────
        // Five evenly-spaced buttons that are noticeably taller than the
        // normal row height so the icons read at a glance. The user
        // explicitly asked for the reload / cycle icons (and the G / T / C
        // visibility toggles) to be placed under "Enable auto kit detect"
        // and made bigger.
        rRef[0]++; // breathing room above the icon strip
        final int iconRowY  = rowY(rRef[0]);
        final int iconBtnH  = BTN_H + 8;        // 28 px — half-row taller
        final int iconCount = 5;
        final int iconGap   = 6;
        final int iconBtnW  = (rowW() - iconGap * (iconCount - 1)) / iconCount;

        // [↻] Reload tier cache.
        safeAdd("reloadCache", () -> {
            int x = rowX();
            ClickableWidget w = ButtonWidget.builder(
                    Text.literal("\u00a7e\u21BB").formatted(Formatting.BOLD),
                    b -> { try { TierTaggerCore.cache().invalidate(); } catch (Throwable ignored) {} })
                .dimensions(x, iconRowY, iconBtnW, iconBtnH)
                .build();
            addTipped(w, "Reload the tier cache.");
        });

        // [◎] Cycle active right gamemode (in-game "I" hotkey).
        safeAdd("cycleRightMode", () -> {
            int x = rowX() + (iconBtnW + iconGap);
            ClickableWidget w = ButtonWidget.builder(
                    Text.literal("\u00a7d\u25C9").formatted(Formatting.BOLD),
                    b -> {
                        TierService rightSvc = cfg.rightServiceEnum();
                        if (rightSvc == null) return;
                        List<String> modes = new ArrayList<>();
                        modes.add("highest");
                        for (String m : rightSvc.modes) if (!modes.contains(m)) modes.add(m);
                        String cur = cfg.rightMode == null ? "highest" : cfg.rightMode.toLowerCase();
                        int idx = modes.indexOf(cur);
                        cfg.rightMode = modes.get((idx < 0 ? 0 : (idx + 1) % modes.size()));
                        cfg.save();
                        rebuildKeepingScroll();
                    })
                .dimensions(x, iconRowY, iconBtnW, iconBtnH)
                .build();
            addTipped(w, "Cycle the active right gamemode (press 'I' in game).");
        });

        // [⚒] Gamemode-icon visibility toggle.
        safeAdd("toggleGameModeIcon", () -> {
            int x = rowX() + 2 * (iconBtnW + iconGap);
            boolean on = !cfg.disableIcons && cfg.showModeIcon;
            ClickableWidget w = ButtonWidget.builder(
                    Text.literal(on ? "\u00a7a\u26ED" : "\u00a77\u26ED").formatted(Formatting.BOLD),
                    b -> {
                        boolean nowOn = !(!cfg.disableIcons && cfg.showModeIcon);
                        cfg.disableIcons = !nowOn;
                        cfg.showModeIcon = nowOn;
                        cfg.save();
                        rebuildKeepingScroll();
                    })
                .dimensions(x, iconRowY, iconBtnW, iconBtnH)
                .build();
            addTipped(w, "Disable the gamemode icon next to the tier.");
        });

        // [≡] Tab-list visibility toggle.
        safeAdd("toggleTablist", () -> {
            int x = rowX() + 3 * (iconBtnW + iconGap);
            boolean on = cfg.showInTab;
            ClickableWidget w = ButtonWidget.builder(
                    Text.literal(on ? "\u00a7a\u2630" : "\u00a77\u2630").formatted(Formatting.BOLD),
                    b -> {
                        cfg.showInTab = !cfg.showInTab;
                        cfg.save();
                        rebuildKeepingScroll();
                    })
                .dimensions(x, iconRowY, iconBtnW, iconBtnH)
                .build();
            addTipped(w, "Disable Tiers on the tab list.");
        });

        // [✎] Chat visibility toggle.
        safeAdd("toggleChat", () -> {
            int x = rowX() + 4 * (iconBtnW + iconGap);
            boolean on = !cfg.disableInChat;
            ClickableWidget w = ButtonWidget.builder(
                    Text.literal(on ? "\u00a7a\u270E" : "\u00a77\u270E").formatted(Formatting.BOLD),
                    b -> {
                        cfg.disableInChat = !cfg.disableInChat;
                        cfg.save();
                        rebuildKeepingScroll();
                    })
                .dimensions(x, iconRowY, iconBtnW, iconBtnH)
                .build();
            addTipped(w, "Disable Tiers in chat.");
        });

        // The icon row uses iconBtnH (28 px) which is taller than ROW_H (24 px),
        // so consume an extra rRef++ to give the next widget vertical clearance.
        rRef[0]++;
        rRef[0]++;

        // ── Advanced Settings (renamed from "Advanced — Left/Right Routing…") ─
        rRef[0]++; // breathing room
        final int advY = rowY(rRef[0]);
        safeAdd("advanced", () -> {
            ClickableWidget w = ButtonWidget.builder(
                    Text.literal("Advanced Settings"),
                    b -> openAdvancedRouting(cfg))
                .dimensions(rowX(), advY, rowW(), BTN_H)
                .build();
            addTipped(w,
                "Open the per-service Left / Right tierlist picker. " +
                "Use this if you want PvPTiers on one side and OuterTiers on the other.");
        });
        rRef[0]++;
    }

    /**
     * Drop-in modal that exposes the old per-service Left/Right picker we
     * removed from the main "Tiers Config" view. Keeps the power-user
     * routing controls reachable without cluttering the redesigned screen.
     */
    private void openAdvancedRouting(TierConfig cfg) {
        // Cheap inline approach: switch to a plain settings sub-screen by
        // toggling a rendering flag and rebuilding. Simpler and safer than
        // pushing a whole new Screen subclass for a few buttons.
        advancedRouting = true;
        rebuildKeepingScroll();
    }

    /** When true, the "Tiers Config" tab renders the legacy routing picker. */
    private boolean advancedRouting = false;

    /**
     * Legacy per-service Left/Right routing picker. Reachable via the
     * "Advanced — Left/Right Routing…" button on the redesigned Tiers
     * Config tab. Constraint: a service can occupy at most one slot
     * (Left ≠ Right) — clicking the opposite arrow auto-swaps the
     * previous occupant so the constraint stays satisfied.
     */
    private void buildAdvancedRoutingView(TierConfig cfg) {
        final int[] rRef = { 0 };

        // ── Back button ───────────────────────────────────────────────────
        final int backY = rowY(rRef[0]++);
        safeAdd("backFromAdvanced", () -> {
            ClickableWidget w = ButtonWidget.builder(
                    Text.literal("\u00a7e\u2190 Back"),
                    b -> { advancedRouting = false; rebuildKeepingScroll(); })
                .dimensions(rowX(), backY, rowW(), BTN_H)
                .build();
            addTipped(w, "Return to the main Tiers Config screen.");
        });
        rRef[0]++;

        addSectionHeader(rRef[0], "\u2014 Badge Assignment (Left \u2260 Right) \u2014");
        rRef[0]++;

        // Status row: "Left: MCTiers   |   Right: OuterTiers"
        final int rStatus = rRef[0]++;
        TierService leftSvc  = cfg.leftServiceEnum();
        TierService rightSvc = cfg.rightServiceEnum();
        final String leftLabel  = "Left:  "  + (leftSvc  != null ? leftSvc.displayName  : "-");
        final String rightLabel = "Right: " + (rightSvc != null ? rightSvc.displayName : "-");
        final int leftColor  = leftSvc  != null ? rgb(leftSvc.accentArgb)  : 0xFFFFFFFF;
        final int rightColor = rightSvc != null ? rgb(rightSvc.accentArgb) : 0xFFFFFFFF;
        safeAdd("statusLeft", () -> {
            ClickableWidget w = ButtonWidget.builder(
                    Text.literal(leftLabel).withColor(leftColor), b -> {})
                .dimensions(colX(0), rowY(rStatus), BTN_W, BTN_H).build();
            addTipped(w, "Currently displayed on the LEFT side of player names.");
        });
        safeAdd("statusRight", () -> {
            ClickableWidget w = ButtonWidget.builder(
                    Text.literal(rightLabel).withColor(rightColor), b -> {})
                .dimensions(colX(1), rowY(rStatus), BTN_W, BTN_H).build();
            addTipped(w, "Currently displayed on the RIGHT side of player names.");
        });

        rRef[0]++;
        addSectionHeader(rRef[0], "\u2014 Available Tierlists \u2014");
        rRef[0]++;

        final int sideBtnW = 32;
        final int sideGap  = 6;
        final int centerW  = rowW() - 2 * (sideBtnW + sideGap);
        TierService[] svcs = TierService.values();

        for (int i = 0; i < svcs.length; i++) {
            final TierService svc = svcs[i];
            final int row = rRef[0]++;
            final boolean isLeft  = svc.id.equalsIgnoreCase(cfg.leftService);
            final boolean isRight = svc.id.equalsIgnoreCase(cfg.rightService);

            String suffix = "";
            if (isLeft  && isRight) suffix = "  (Left & Right)";
            else if (isLeft)        suffix = "  (Left)";
            else if (isRight)       suffix = "  (Right)";
            final String labelText = svc.displayName + suffix;

            safeAdd("set-left:" + svc.id, () -> {
                ClickableWidget w = ButtonWidget.builder(
                        Text.literal(isLeft ? "[\u2190]" : "\u2190"),
                        b -> {
                            try {
                                String prevLeft = cfg.leftService;
                                cfg.leftService = svc.id;
                                if (cfg.rightService != null && cfg.rightService.equalsIgnoreCase(svc.id)) {
                                    cfg.rightService = (prevLeft != null && !prevLeft.equalsIgnoreCase(svc.id))
                                        ? prevLeft : nextDifferent(svc.id);
                                }
                                cfg.save();
                                try { TierTaggerCore.cache().invalidate(); } catch (Throwable ignored) {}
                            } catch (Throwable ignored) {}
                            rebuildKeepingScroll();
                        })
                    .dimensions(colX(0), rowY(row), sideBtnW, BTN_H).build();
                addTipped(w, "Show " + svc.displayName + " on the LEFT side of player names.");
            });

            safeAdd("svc-row:" + svc.id, () -> {
                ClickableWidget w = ButtonWidget.builder(
                        Text.literal(labelText).withColor(rgb(svc.accentArgb)),
                        b -> {
                            cfg.primaryService = svc.id;
                            cfg.save();
                            try { TierTaggerCore.cache().invalidate(); } catch (Throwable ignored) {}
                            rebuildKeepingScroll();
                        })
                    .dimensions(colX(0) + sideBtnW + sideGap, rowY(row), centerW, BTN_H).build();
                addTipped(w, "Click to make " + svc.displayName + " the primary tierlist " +
                    "(used by the active service display on the main Tiers Config screen).");
            });

            safeAdd("set-right:" + svc.id, () -> {
                ClickableWidget w = ButtonWidget.builder(
                        Text.literal(isRight ? "[\u2192]" : "\u2192"),
                        b -> {
                            try {
                                String prevRight = cfg.rightService;
                                cfg.rightService = svc.id;
                                if (cfg.leftService != null && cfg.leftService.equalsIgnoreCase(svc.id)) {
                                    cfg.leftService = (prevRight != null && !prevRight.equalsIgnoreCase(svc.id))
                                        ? prevRight : nextDifferent(svc.id);
                                }
                                cfg.save();
                                try { TierTaggerCore.cache().invalidate(); } catch (Throwable ignored) {}
                            } catch (Throwable ignored) {}
                            rebuildKeepingScroll();
                        })
                    .dimensions(colX(1) + BTN_W - sideBtnW, rowY(row), sideBtnW, BTN_H).build();
                addTipped(w, "Show " + svc.displayName + " on the RIGHT side of player names.");
            });
        }
    }

    private void rebuildKeepingScroll() {
        int s = scrollY;
        this.clearChildren();
        try { buildWidgets(); } catch (Throwable ignored) {}
        scrollY = s;
        scrollByTab[currentTab] = s;
    }

    /**
     * Returns the id of any {@link TierService} that is NOT the given id.
     * Used as a safety fallback when swapping Left/Right assignments would
     * otherwise leave one slot equal to the other.
     */
    private static String nextDifferent(String id) {
        for (TierService s : TierService.values()) {
            if (!s.id.equalsIgnoreCase(id)) return s.id;
        }
        return id;
    }

    private static String prettyMode(String mode) {
        if (mode == null || mode.isEmpty()) return "?";
        if ("highest".equalsIgnoreCase(mode)) return "Highest";
        return Character.toUpperCase(mode.charAt(0)) + mode.substring(1).replace('_', ' ');
    }

    private static String prettyTtl(int seconds) {
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + " min";
        return (seconds / 3600) + " h";
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

            int totalW = BTN_W * 2 + BTN_GAP;
            int panelW = Math.min(PANEL_W_MAX, this.width - 24);
            panelW = Math.max(panelW, totalW + 24);
            int panelX  = (this.width  - panelW) / 2;
            int panelTop    = 8;
            int panelBottomY = this.height - 8;

            // 1. Panel background (full height).
            fillRect(ctx, panelX, panelTop, panelX + panelW, panelBottomY, BG_PANEL);
            outlineRect(ctx, panelX, panelTop, panelW, panelBottomY - panelTop, BG_PANEL_BORDER);

            // 2. Section-header text inside scissor (scrolls with content).
            ctx.enableScissor(panelX + 1, bodyTop, panelX + panelW - 1, bodyBottom);
            for (int i = 0; i < sectionHeaders.size(); i++) {
                int y = sectionHeaders.get(i)[0];
                if (y < bodyTop - 8 || y > bodyBottom) continue;
                String label = i < sectionHeadersText.size() ? sectionHeadersText.get(i) : "\u2014";
                ctx.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal(label).formatted(Formatting.YELLOW),
                    this.width / 2, y, FG_SECTION);
            }
            ctx.disableScissor();

            // 3. Widgets rendered WITHOUT scissor so the bottom action row is
            //    never clipped. Anything that leaks above the body is repainted
            //    by the title strip / tab strip below.
            super.render(ctx, mouseX, mouseY, delta);

            // 4. Tier Colors tab: per-row tier label + colour swatch overlay.
            if (currentTab == 1) {
                ctx.enableScissor(panelX + 1, bodyTop, panelX + panelW - 1, bodyBottom);
                for (ColorRow cr : colorRows) {
                    if (cr == null || cr.field == null) continue;
                    int fx = cr.field.getX();
                    int fy = cr.field.getY();
                    int fw = cr.field.getWidth();
                    int fh = BTN_H;
                    if (fy + fh < bodyTop || fy > bodyBottom) continue;
                    // Tier label, right-aligned inside the field.
                    int labelW = this.textRenderer.getWidth(cr.tierKey);
                    ctx.drawTextWithShadow(this.textRenderer,
                        Text.literal(cr.tierKey).formatted(Formatting.GRAY),
                        fx + fw - labelW - 6, fy + (fh - 8) / 2, 0xFF9AA0AA);
                    // Live colour swatch to the right of the field.
                    int swatchX = fx + fw + 6;
                    int swatchY = fy;
                    int swatchSize = fh;
                    int argb = TierConfig.parseHexArgb(cr.field.getText());
                    fillRect(ctx, swatchX, swatchY, swatchX + swatchSize, swatchY + swatchSize, argb);
                    outlineRect(ctx, swatchX, swatchY, swatchSize, swatchSize, 0xFF000000);
                }
                ctx.disableScissor();
            }

            // 5. Re-draw the title strip ON TOP to cover any scrolled widget
            //    that crept above bodyTop. We paint a soft vertical
            //    gradient (lighter at the top, darker at the bottom) and
            //    cap it with a thin yellow accent line — same style the
            //    user described as "ein meisterwerk", but still subtle
            //    enough to read in any colour scheme.
            int titleX1 = panelX + 1;
            int titleX2 = panelX + panelW - 1;
            int titleY1 = panelTop + 1;
            int titleY2 = TAB_STRIP_TOP;
            verticalGradient(ctx, titleX1, titleY1, titleX2, titleY2, BG_HEADER_TOP, BG_HEADER_BOT);
            // Soft accent glow line just below the title text.
            fillRect(ctx, titleX1, titleY2 - 2, titleX2, titleY2 - 1, ACCENT_SOFT);
            fillRect(ctx, titleX1, titleY2 - 1, titleX2, titleY2,     ACCENT);

            ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("TierTagger").formatted(Formatting.WHITE, Formatting.BOLD),
                this.width / 2, panelTop + 6, 0xFFFFFFFF);
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("v" + TierTaggerCore.MOD_VERSION +
                    "  \u00B7  /tiertagger help for commands")
                    .withColor(rgb(FG_FAINT)),
                this.width / 2, panelTop + 16, FG_FAINT);

            // 6. Active-tab accent: thin yellow underline beneath the
            //    currently-selected tab. The tab buttons themselves are
            //    already rendered by super.render(); this just adds the
            //    glow strip so users can tell at a glance which tab is
            //    active even with the simplified labels.
            if (currentTab >= 0 && currentTab < tabBounds.length) {
                int[] tb = tabBounds[currentTab];
                int aX1 = tb[0] + 4;
                int aX2 = tb[0] + tb[2] - 4;
                int aY  = tb[1] + tb[3];
                fillRect(ctx, aX1, aY,     aX2, aY + 1, ACCENT);
                fillRect(ctx, aX1, aY + 1, aX2, aY + 2, ACCENT_SOFT);
            }

            // 7. Scroll indicator.
            if (maxScroll > 0) {
                int trackX  = panelX + panelW - 5;
                int trackTop = bodyTop;
                int trackH  = bodyBottom - bodyTop;
                fillRect(ctx, trackX, trackTop, trackX + 3, trackTop + trackH, 0x40FFFFFF);
                int thumbH = Math.max(20, trackH * trackH / Math.max(1, trackH + maxScroll));
                int thumbY = trackTop + (int)((long)(trackH - thumbH) * scrollY / Math.max(1, maxScroll));
                fillRect(ctx, trackX, thumbY, trackX + 3, thumbY + thumbH, 0xFFAAAAAA);
            }

            if (lastInitError != null) {
                drawErrorOverlay(ctx, "Config init failed", lastInitError);
            }
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] config screen render", t);
            try { drawErrorOverlay(ctx, "Config render failed",
                t.getClass().getSimpleName() + ": " +
                (t.getMessage() == null ? "(no message)" : t.getMessage())); }
            catch (Throwable ignored) {}
        }
    }

    private void renderTabStrip(DrawContext ctx, int panelX, int panelW) {
        int innerLeft  = panelX + 4;
        int innerRight = panelX + panelW - 4;
        int innerW     = innerRight - innerLeft;
        int gap        = 4;
        int tabW       = (innerW - gap * (TAB_LABELS.length - 1)) / TAB_LABELS.length;
        int tabY       = TAB_STRIP_TOP;

        // Cover the tab strip area first so widgets that scroll past don't bleed through.
        fillRect(ctx, panelX + 1, tabY - 2, panelX + panelW - 1, tabY + TAB_H + 2, BG_HEADER);

        for (int i = 0; i < TAB_LABELS.length; i++) {
            int tx = innerLeft + i * (tabW + gap);
            int ty = tabY;
            tabBounds[i][0] = tx;
            tabBounds[i][1] = ty;
            tabBounds[i][2] = tabW;
            tabBounds[i][3] = TAB_H;

            boolean active = (i == currentTab);
            int bg = active ? BG_TAB_ACTIVE : BG_TAB_INACTIVE;
            fillRect(ctx, tx, ty, tx + tabW, ty + TAB_H, bg);
            outlineRect(ctx, tx, ty, tabW, TAB_H, BG_PANEL_BORDER);
            // White underline on the active tab.
            if (active) {
                fillRect(ctx, tx + 2, ty + TAB_H - 2, tx + tabW - 2, ty + TAB_H, 0xFFFFFFFF);
            }
            int textColor = active ? 0xFFFFFFFF : 0xFFB8BCC4;
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(TAB_LABELS[i]).formatted(active ? Formatting.WHITE : Formatting.GRAY),
                tx + tabW / 2, ty + (TAB_H - 8) / 2, textColor);
        }
    }

    private void drawErrorOverlay(DrawContext ctx, String title, String detail) {
        int cx = this.width / 2;
        int cy = this.height / 2;
        int w  = Math.min(this.width - 40, 360);
        fillRect(ctx, cx - w / 2, cy - 30, cx + w / 2, cy + 30, 0xCC110000);
        outlineRect(ctx, cx - w / 2, cy - 30, w, 60, 0xFFFF5555);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
            Text.literal(title).formatted(Formatting.RED, Formatting.BOLD), cx, cy - 18, 0xFFFF5555);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
            Text.literal(detail).formatted(Formatting.WHITE), cx, cy - 4, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
            Text.literal("See latest.log for the full stack trace").formatted(Formatting.GRAY),
            cx, cy + 12, 0xFFAAAAAA);
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

    /**
     * Paint a smooth vertical gradient from {@code topArgb} at {@code y1}
     * down to {@code botArgb} at {@code y2}. Uses 1-pixel horizontal
     * stripes with linearly interpolated ARGB so it stays crisp at any
     * panel size without needing a texture asset.
     */
    private static void verticalGradient(DrawContext ctx, int x1, int y1, int x2, int y2,
                                         int topArgb, int botArgb) {
        int h = y2 - y1;
        if (h <= 0) return;
        int aT = (topArgb >>> 24) & 0xFF, aB = (botArgb >>> 24) & 0xFF;
        int rT = (topArgb >>> 16) & 0xFF, rB = (botArgb >>> 16) & 0xFF;
        int gT = (topArgb >>>  8) & 0xFF, gB = (botArgb >>>  8) & 0xFF;
        int bT =  topArgb         & 0xFF, bB =  botArgb         & 0xFF;
        for (int i = 0; i < h; i++) {
            float t = (float) i / Math.max(1, h - 1);
            int a = (int) (aT + (aB - aT) * t) & 0xFF;
            int r = (int) (rT + (rB - rT) * t) & 0xFF;
            int g = (int) (gT + (gB - gT) * t) & 0xFF;
            int b = (int) (bT + (bB - bT) * t) & 0xFF;
            int argb = (a << 24) | (r << 16) | (g << 8) | b;
            try { ctx.fill(x1, y1 + i, x2, y1 + i + 1, argb); } catch (Throwable ignored) {}
        }
    }

    private void closeSafely() {
        MinecraftClient mc = this.client != null ? this.client : MinecraftClient.getInstance();
        if (mc != null) mc.setScreen(parent);
    }

    @Override
    public void close() { closeSafely(); }

    @SuppressWarnings("unused")
    private static final Set<String> _USED = new LinkedHashSet<>();
}

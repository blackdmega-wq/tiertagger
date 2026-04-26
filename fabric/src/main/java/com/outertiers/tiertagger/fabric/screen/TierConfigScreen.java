package com.outertiers.tiertagger.fabric.screen;

import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierService;
import com.outertiers.tiertagger.common.TierTaggerCore;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
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
    private static final int BG_TAB_INACTIVE = 0xFF1F232C;
    private static final int BG_TAB_ACTIVE   = 0xFF0E1116;
    private static final int FG_FAINT        = 0xFF9AA0AA;
    private static final int FG_SECTION      = 0xFFFFAA00;

    private static final int TAB_H           = 22;
    private static final int TAB_STRIP_TOP   = 30;
    private static final String[] TAB_LABELS = { "Settings", "Tier Colors", "Tierlists" };

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

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // Tab-strip hit testing happens before super so the tab buttons always win.
        if (button == 0) {
            for (int i = 0; i < tabBounds.length; i++) {
                int[] b = tabBounds[i];
                if (b[2] <= 0 || b[3] <= 0) continue;
                if (mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3]) {
                    switchTab(i);
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
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

        switch (currentTab) {
            case 1:  buildTierColorsTab(cfg); break;
            case 2:  buildTierlistsTab(cfg);  break;
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
                                cfg.primaryService = TierService.OUTERTIERS.id;
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
    }

    // ─────────────────────────────────────────────────────────────────────
    // Tab 0: Settings (legacy content, every toggle the user already had)
    // ─────────────────────────────────────────────────────────────────────
    private void buildSettingsTab(TierConfig cfg) {
        final int[] rRef = { 0 };

        addSectionHeader(rRef[0], "\u2014 Badge Services \u2014");
        rRef[0]++;
        safeAdd("leftService", () -> this.addDrawableChild(
            CyclingButtonWidget.<TierService>builder(
                    s -> Text.literal(s.displayName).withColor(rgb(s.accentArgb)),
                    cfg.leftServiceEnum())
                .values(TierService.values())
                .build(colX(0), rowY(rRef[0]), BTN_W, BTN_H,
                    Text.literal("Left Badge"),
                    (b, v) -> { cfg.leftService = v.id; cfg.save();
                        this.clearChildren(); try { buildWidgets(); } catch (Throwable ignored) {} })));
        safeAdd("rightService", () -> this.addDrawableChild(
            CyclingButtonWidget.<TierService>builder(
                    s -> Text.literal(s.displayName).withColor(rgb(s.accentArgb)),
                    cfg.rightServiceEnum())
                .values(TierService.values())
                .build(colX(1), rowY(rRef[0]), BTN_W, BTN_H,
                    Text.literal("Right Badge"),
                    (b, v) -> { cfg.rightService = v.id; cfg.save();
                        this.clearChildren(); try { buildWidgets(); } catch (Throwable ignored) {} })));
        rRef[0]++;

        safeAdd("primaryService", () -> this.addDrawableChild(
            CyclingButtonWidget.<TierService>builder(
                    s -> Text.literal(s.displayName).withColor(rgb(s.accentArgb)),
                    cfg.primaryServiceEnum())
                .values(TierService.values())
                .build(colX(0), rowY(rRef[0]), BTN_W, BTN_H,
                    Text.literal("Primary Service"),
                    (b, v) -> { cfg.primaryService = v.id; cfg.save(); })));
        safeAdd("displayMode", () -> {
            List<String> modes = new ArrayList<>();
            modes.add("highest");
            modes.addAll(TierService.allKnownModes());
            String initial = cfg.displayMode == null ? "highest" : cfg.displayMode.toLowerCase();
            if (!modes.contains(initial)) modes.add(initial);
            this.addDrawableChild(
                CyclingButtonWidget.<String>builder(s -> Text.literal(prettyMode(s)), initial)
                    .values(modes)
                    .build(colX(1), rowY(rRef[0]), BTN_W, BTN_H,
                        Text.literal("Display Mode"),
                        (b, v) -> { cfg.displayMode = v; cfg.save(); }));
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
            this.addDrawableChild(
                CyclingButtonWidget.<String>builder(s -> Text.literal(prettyMode(s)), initial)
                    .values(modes)
                    .build(colX(0), rowY(rRef[0]), BTN_W, BTN_H,
                        Text.literal("Left Mode"),
                        (b, v) -> { cfg.leftMode = v; cfg.save(); }));
        });
        safeAdd("rightMode", () -> {
            TierService rightSvc = cfg.rightServiceEnum();
            List<String> modes = new ArrayList<>();
            modes.add("highest");
            for (String m : rightSvc.modes) if (!modes.contains(m)) modes.add(m);
            String initial = cfg.rightMode == null ? "highest" : cfg.rightMode.toLowerCase();
            if (!modes.contains(initial)) modes.add(initial);
            this.addDrawableChild(
                CyclingButtonWidget.<String>builder(s -> Text.literal(prettyMode(s)), initial)
                    .values(modes)
                    .build(colX(1), rowY(rRef[0]), BTN_W, BTN_H,
                        Text.literal("Right Mode"),
                        (b, v) -> { cfg.rightMode = v; cfg.save(); }));
        });
        rRef[0]++;

        addSectionHeader(rRef[0], "\u2014 Where to Show \u2014");
        rRef[0]++;
        safeAdd("showInTab", () -> this.addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.showInTab)
            .build(colX(0), rowY(rRef[0]), BTN_W, BTN_H, Text.literal("Tab Badges"),
                (b, v) -> { cfg.showInTab = v; cfg.save(); })));
        safeAdd("showNametag", () -> this.addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.showNametag)
            .build(colX(1), rowY(rRef[0]), BTN_W, BTN_H, Text.literal("Nametag (F5)"),
                (b, v) -> { cfg.showNametag = v; cfg.save(); })));
        rRef[0]++;

        safeAdd("rightBadgeEnabled", () -> this.addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.rightBadgeEnabled)
            .build(colX(0), rowY(rRef[0]), BTN_W, BTN_H, Text.literal("Dual Badges"),
                (b, v) -> { cfg.rightBadgeEnabled = v; cfg.save(); })));
        safeAdd("showPeak", () -> this.addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.showPeak)
            .build(colX(1), rowY(rRef[0]), BTN_W, BTN_H, Text.literal("Use Peak Tier"),
                (b, v) -> { cfg.showPeak = v; cfg.save(); })));
        rRef[0]++;

        addSectionHeader(rRef[0], "\u2014 Appearance \u2014");
        rRef[0]++;
        safeAdd("coloredBadges", () -> this.addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.coloredBadges)
            .build(colX(0), rowY(rRef[0]), BTN_W, BTN_H, Text.literal("Coloured Badges"),
                (b, v) -> { cfg.coloredBadges = v; cfg.save(); })));
        safeAdd("badgeFormat", () -> {
            List<String> formats = Arrays.asList(TierConfig.BADGE_FORMATS);
            String initialFormat = (cfg.badgeFormat == null || !formats.contains(cfg.badgeFormat))
                ? "bracket" : cfg.badgeFormat;
            this.addDrawableChild(
                CyclingButtonWidget.<String>builder(Text::literal, initialFormat)
                    .values(formats)
                    .build(colX(1), rowY(rRef[0]), BTN_W, BTN_H,
                        Text.literal("Badge Format"),
                        (b, v) -> { cfg.badgeFormat = v; cfg.save(); }));
        });
        rRef[0]++;

        safeAdd("showServiceIcon", () -> this.addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.showServiceIcon)
            .build(colX(0), rowY(rRef[0]), BTN_W, BTN_H, Text.literal("Service Tag"),
                (b, v) -> { cfg.showServiceIcon = v; cfg.save(); })));
        safeAdd("modeIcons", () -> this.addDrawableChild(CyclingButtonWidget.onOffBuilder(!cfg.disableIcons && cfg.showModeIcon)
            .build(colX(1), rowY(rRef[0]), BTN_W, BTN_H, Text.literal("Gamemode Icons"),
                (b, v) -> { cfg.disableIcons = !v; cfg.showModeIcon = v; cfg.save(); })));
        rRef[0]++;

        safeAdd("disableTiers", () -> this.addDrawableChild(CyclingButtonWidget.onOffBuilder(!cfg.disableTiers)
            .build(colX(0), rowY(rRef[0]), BTN_W, BTN_H, Text.literal("Show Tier Text"),
                (b, v) -> { cfg.disableTiers = !v; cfg.save(); })));
        safeAdd("disableAnimations", () -> this.addDrawableChild(CyclingButtonWidget.onOffBuilder(!cfg.disableAnimations)
            .build(colX(1), rowY(rRef[0]), BTN_W, BTN_H, Text.literal("Animations"),
                (b, v) -> { cfg.disableAnimations = !v; cfg.save(); })));
        rRef[0]++;

        safeAdd("fallthrough", () -> this.addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.fallthroughToHighest)
            .build(colX(0), rowY(rRef[0]), BTN_W, BTN_H, Text.literal("Fallback to Highest"),
                (b, v) -> { cfg.fallthroughToHighest = v; cfg.save(); })));
        safeAdd("ttl", () -> {
            List<Integer> ttls = Arrays.asList(60, 180, 300, 600, 1800, 3600);
            int initial = ttls.contains(cfg.cacheTtlSeconds) ? cfg.cacheTtlSeconds : 300;
            this.addDrawableChild(
                CyclingButtonWidget.<Integer>builder(s -> Text.literal(prettyTtl(s)), initial)
                    .values(ttls)
                    .build(colX(1), rowY(rRef[0]), BTN_W, BTN_H,
                        Text.literal("Cache TTL"),
                        (b, v) -> { cfg.cacheTtlSeconds = v; cfg.save(); }));
        });
        rRef[0]++;

        addSectionHeader(rRef[0], "\u2014 Enabled Services \u2014");
        rRef[0]++;
        TierService[] svcs = TierService.values();
        for (int i = 0; i < svcs.length; i++) {
            final TierService svc = svcs[i];
            final int col = i % 2;
            if (col == 0 && i > 0) rRef[0]++;
            safeAdd("svc:" + svc.id, () -> this.addDrawableChild(
                CyclingButtonWidget.onOffBuilder(cfg.isServiceEnabled(svc))
                    .build(colX(col), rowY(rRef[0]), BTN_W, BTN_H,
                        Text.literal(svc.displayName).withColor(rgb(svc.accentArgb)),
                        (b, v) -> {
                            cfg.setServiceEnabled(svc, v);
                            cfg.save();
                            try { TierTaggerCore.cache().invalidate(); } catch (Throwable ignored) {}
                        })));
        }
        rRef[0]++;

        addSectionHeader(rRef[0], "\u2014 Mode Filters \u2014");
        rRef[0]++;
        safeAdd("tabModes", () -> this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Tab Modes\u2026"),
                b -> {
                    MinecraftClient mc = this.client != null ? this.client : MinecraftClient.getInstance();
                    if (mc != null) mc.setScreen(new ModeFilterScreen(this, true));
                })
            .dimensions(colX(0), rowY(rRef[0]), BTN_W, BTN_H).build()));
        safeAdd("nametagModes", () -> this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Nametag Modes\u2026"),
                b -> {
                    MinecraftClient mc = this.client != null ? this.client : MinecraftClient.getInstance();
                    if (mc != null) mc.setScreen(new ModeFilterScreen(this, false));
                })
            .dimensions(colX(1), rowY(rRef[0]), BTN_W, BTN_H).build()));
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
    // Tab 2: Tierlists — one selectable button per service
    // ─────────────────────────────────────────────────────────────────────
    private void buildTierlistsTab(TierConfig cfg) {
        TierService[] svcs = TierService.values();
        final int[] rRef = { 0 };
        for (int i = 0; i < svcs.length; i++) {
            final TierService svc = svcs[i];
            final int row = rRef[0]++;
            final boolean selected = svc.id.equalsIgnoreCase(cfg.primaryService);
            String label = svc.displayName + (selected ? "  (selected)" : "");
            safeAdd("tl:" + svc.id, () -> this.addDrawableChild(ButtonWidget.builder(
                    Text.literal(label).withColor(rgb(svc.accentArgb)),
                    b -> {
                        cfg.primaryService = svc.id;
                        cfg.save();
                        try { TierTaggerCore.cache().invalidate(); } catch (Throwable ignored) {}
                        this.clearChildren();
                        try { buildWidgets(); } catch (Throwable ignored) {}
                    })
                .dimensions(rowX(), rowY(row), rowW(), BTN_H).build()));
        }
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
            //    that crept above bodyTop.
            fillRect(ctx, panelX + 1, panelTop + 1, panelX + panelW - 1, TAB_STRIP_TOP, BG_HEADER);
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("TierTagger").formatted(Formatting.WHITE, Formatting.BOLD),
                this.width / 2, panelTop + 6, 0xFFFFFFFF);
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("v" + TierTaggerCore.MOD_VERSION +
                    "  \u00B7  /tiertagger help for chat commands")
                    .withColor(rgb(FG_FAINT)),
                this.width / 2, panelTop + 16, FG_FAINT);

            // 6. Tab strip — cover anything beneath, then draw three tabs.
            renderTabStrip(ctx, panelX, panelW);

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

    private void closeSafely() {
        MinecraftClient mc = this.client != null ? this.client : MinecraftClient.getInstance();
        if (mc != null) mc.setScreen(parent);
    }

    @Override
    public void close() { closeSafely(); }

    @SuppressWarnings("unused")
    private static final Set<String> _USED = new LinkedHashSet<>();
}

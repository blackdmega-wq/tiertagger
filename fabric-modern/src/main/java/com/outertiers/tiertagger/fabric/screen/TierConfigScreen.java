package com.outertiers.tiertagger.fabric.screen;

import com.outertiers.tiertagger.common.PlayerData;
import com.outertiers.tiertagger.common.Ranking;
import com.outertiers.tiertagger.common.ServiceData;
import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierService;
import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.fabric.BadgeRenderer;
import com.outertiers.tiertagger.fabric.SkinFetcher;
import com.outertiers.tiertagger.fabric.TierKeybinds;
import com.outertiers.tiertagger.fabric.compat.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.Util;

import java.util.Set;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

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

    // ── Live nametag preview ────────────────────────────────────────────────
    /**
     * Display / lookup name used by the live preview card.
     *
     * v1.21.11.48: previously hard-coded to "Outversal" so every user saw
     * the same skin in the preview. The user asked for the preview to show
     * THEIR currently-worn Minecraft skin instead, so we now look up the
     * client's local username on demand and fall back to "Outversal" when
     * we can't (e.g. screen opened from the title screen with no session).
     * The skin itself is fetched by mc-heads.net via {@link SkinFetcher},
     * which keys off the username — so swapping the name swaps the skin.
     */
    private static final String PREVIEW_NAME_FALLBACK = "Outversal";

    private static String previewName() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                if (mc.player != null && mc.player.getGameProfile() != null) {
                    String n = com.outertiers.tiertagger.fabric.compat.Profiles
                            .name(mc.player.getGameProfile());
                    if (n != null && !n.isBlank()) return n;
                }
                if (mc.player != null) {
                    try {
                        String n = mc.player.getName().getString();
                        if (n != null && !n.isBlank()) return n;
                    } catch (Throwable ignored) {}
                }
                try {
                    Object user = Minecraft.class.getMethod("getUser").invoke(mc);
                    if (user != null) {
                        String n = (String) user.getClass().getMethod("getName").invoke(user);
                        if (n != null && !n.isBlank()) return n;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return PREVIEW_NAME_FALLBACK;
    }

    /** Tracked so {@link #ensurePreviewData()} rebuilds when the local
     *  player's name changes (e.g. user switched accounts). */
    private static volatile String LAST_PREVIEW_NAME = null;
    /** Bounds of the preview panel. Set to {@code w<=0} when no preview is shown. */
    private int previewX = 0, previewY = 0, previewW = 0, previewH = 0;
    /** Cached fake PlayerData populated with one HT3 ranking per gamemode in
     *  every service so {@link BadgeRenderer#wrapNametag} always has something
     *  to display regardless of which left/right service the user picks. */
    private static volatile PlayerData PREVIEW_DATA = null;

    /**
     * Image-icon overlays drawn on top of plain {@link Button}s after
     * {@code super.render(...)}. Each entry is {@code [button, itemStack, dimmedSupplier]}
     * — the supplier may be {@code null} for "always-on" action buttons
     * (e.g. reload cache). Subclassing {@code Button} directly to add
     * the icon doesn't work cleanly across MC 1.21.5+ because the
     * {@code drawIcon} abstract method and the nested {@code Button.Text}
     * type shadow the {@code net.minecraft.network.chat.Component} import — overlay-after
     * is the simplest cross-version-friendly approach.
     */
    private final List<Object[]> iconOverlays = new ArrayList<>();

    /**
     * Square 18x18 link buttons rendered in the title strip. Each entry is
     * {@code [button, brandColor, label, optionalTextureId]}. Painted in
     * render() with a coloured square background and either a bold initial
     * or the OuterTiers logo texture overlaid on top of the vanilla button.
     */
    private final List<Object[]> headerLinks = new ArrayList<>();

    /**
     * Identity-set of header link {@link Button}s. Used by
     * {@link #clipScrollableWidgets()} to exempt the Discord / OuterTiers /
     * Linktree buttons from the body-clip — they sit at y &lt; bodyTop so
     * the strict containment check would otherwise mark them invisible /
     * inactive (which is the v1.21.11.38 bug where the header link
     * buttons looked clickable but did nothing).
     */
    private final Set<AbstractWidget> pinnedWidgets =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** ResourceLocation for the bundled OuterTiers logo PNG used in the header. */
    private static final ResourceLocation OT_LOGO = ResourceLocation.fromNamespaceAndPath("tiertagger", "textures/logo/outertiers.png");

    /** Brand colours for the three link buttons. */
    private static final int DISCORD_BLURPLE = 0xFF5865F2;
    private static final int OT_BRAND        = 0xFF1A1F28;
    private static final int LINKTREE_GREEN  = 0xFF43E660;
    private static final int MODRINTH_GREEN  = 0xFF1BD96A;

    public TierConfigScreen(Screen parent) {
        super(Component.literal("TierTagger \u2014 Settings"));
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

    /** Lighten an ARGB colour towards white by {@code amt} (0..1). Used
     *  to produce a hover state for the brand-coloured header link buttons. */
    private static int lighten(int argb, float amt) {
        if (amt <= 0f) return argb;
        if (amt >= 1f) amt = 1f;
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8)  & 0xFF;
        int b =  argb         & 0xFF;
        r = Math.min(255, Math.round(r + (255 - r) * amt));
        g = Math.min(255, Math.round(g + (255 - g) * amt));
        b = Math.min(255, Math.round(b + (255 - b) * amt));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private volatile String lastInitError = null;

    // ── Cycle-Mode-Key click-to-bind state ──────────────────────────────
    // When the user clicks the "Cycle Mode Key" button, CAPTURING_CYCLE_KEY
    // flips to true. The next physical key press is then captured by
    // KeyboardMixin (which hooks the GLFW-stable Keyboard.onKey callback,
    // so we don't have to override Screen.keyPressed — its signature changes
    // between MC versions). ESC cancels capture without changing the bind.
    public static volatile boolean CAPTURING_CYCLE_KEY = false;
    public static volatile Button CYCLE_KEY_BUTTON = null;

    /** Refreshes the cycle-key button label. Safe to call from any thread
     *  that has access to the Minecraft client. */
    public static void refreshCycleKeyButtonLabel() {
        Button b = CYCLE_KEY_BUTTON;
        if (b == null) return;
        String base;
        try { base = TierKeybinds.keyLabel(TierKeybinds.getCycleKeyCode()); }
        catch (Throwable t) { base = "?"; }
        String msg = CAPTURING_CYCLE_KEY
            ? "Cycle Mode Key: > " + base + " <  (press a key)"
            : "Cycle Mode Key: " + base;
        b.setMessage(Component.literal(msg));
    }

    /** Build the button label for initial creation. */
    private String cycleKeyLabel() {
        String base;
        try { base = TierKeybinds.keyLabel(TierKeybinds.getCycleKeyCode()); }
        catch (Throwable t) { base = "?"; }
        if (CAPTURING_CYCLE_KEY) return "Cycle Mode Key: > " + base + " <  (press a key)";
        return "Cycle Mode Key: " + base;
    }

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
            this.addDrawableChild(Button.builder(
                    Component.literal("Done"), b -> closeSafely())
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
     * {@link Button} so we don't need to override {@code mouseClicked}
     * (whose signature changes between MC versions). The custom tab strip
     * background is rendered separately in {@link #render} on top of the panel
     * but BEHIND these buttons so the labels stay clickable.
     */
    /**
     * Build the three square link buttons that live in the title strip:
     * Discord, OuterTiers website, Linktree. Each button is a plain
     * {@link Button} with empty text — the coloured square + initial
     * (or OuterTiers logo) is painted on top in {@link #render}. Clicking
     * a button opens the matching URL through {@link Util#getOperatingSystem()}.
     */
    private void addHeaderLinkButtons() {
        int totalW  = BTN_W * 2 + BTN_GAP;
        int panelW  = Math.min(PANEL_W_MAX, this.width - 24);
        panelW      = Math.max(panelW, totalW + 24);
        int panelX  = (this.width - panelW) / 2;
        int panelTop = 8;

        int btnSize = 18;
        int gap     = 4;
        int rightEdge = panelX + panelW - 6;
        int yTop      = panelTop + 4;

        // Right-to-left: Linktree, OuterTiers, Discord (so reading order is
        // Discord → OuterTiers → Linktree, left to right).
        Object[][] specs = new Object[][] {
            { "https://discord.gg/6eAaPqg4up",     DISCORD_BLURPLE, "D", null },
            { "https://outertiers.onrender.com/",  OT_BRAND,        "O", OT_LOGO },
            { "https://linktr.ee/Outversal",       LINKTREE_GREEN,  "L", null },
            { "https://modrinth.com/mod/tiertagger", MODRINTH_GREEN, "M", null },
        };

        for (int i = specs.length - 1; i >= 0; i--) {
            final String url    = (String)     specs[i][0];
            final int    color  = (Integer)    specs[i][1];
            final String label  = (String)     specs[i][2];
            final ResourceLocation tx = (ResourceLocation) specs[i][3];

            int btnX = rightEdge - btnSize;
            rightEdge -= (btnSize + gap);

            Button btn;
            try {
                btn = Button.builder(Component.empty(), b -> openUrl(url))
                        .dimensions(btnX, yTop, btnSize, btnSize)
                        .build();
                this.addDrawableChild(btn);
                String tipText = url.contains("discord")     ? "Join the Discord"
                               : url.contains("linktr")      ? "OuterTiers Linktree"
                               : url.contains("modrinth")    ? "TierTagger on Modrinth"
                               : "Visit the OuterTiers website";
                tip(btn, tipText + "\n" + url);
            } catch (Throwable t) {
                continue;
            }
            headerLinks.add(new Object[]{ btn, color, label, tx });
            pinnedWidgets.add(btn);
        }
    }

    /**
     * Hand the URL to the OS default handler (browser). Wrapped so a
     * sandboxed env / missing handler can't crash the screen.
     */
    private static void openUrl(String url) {
        if (url == null || url.isEmpty()) return;
        try { Util.getOperatingSystem().open(URI.create(url)); }
        catch (Throwable t) {
            try { Util.getOperatingSystem().open(url); }
            catch (Throwable ignored) {
                TierTaggerCore.LOGGER.warn("[TierTagger] could not open URL {}: {}", url, t.toString());
            }
        }
    }

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
            Button btn = Button.builder(
                    Component.literal(TAB_LABELS[i]),
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
    private static void tip(AbstractWidget w, String text) {
        if (w == null || text == null || text.isEmpty()) return;
        try { w.setTooltip(Tooltip.of(Component.literal(text))); }
        catch (Throwable ignored) {}
    }

    /** Helper: add a widget AND attach a tooltip in one call. */
    private <T extends AbstractWidget> T addTipped(T w, String tooltip) {
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
        iconOverlays.clear();
        headerLinks.clear();
        pinnedWidgets.clear();
        // Disable preview by default; only the Tiers Config tab (and its
        // Advanced Settings sub-screen) re-enables it by setting positive
        // bounds at the end of its builder.
        previewW = 0;

        TierConfig cfg = TierTaggerCore.config();
        if (cfg == null) {
            this.addDrawableChild(Button.builder(Component.literal("Done"), b -> closeSafely())
                .dimensions(this.width / 2 - 75, this.height / 2, 150, BTN_H).build());
            return;
        }

        // Header link buttons (Discord / Outertiers / Linktree) sit in the
        // title strip and must be added BEFORE the tab buttons so their
        // hit-rects line up with the painted overlays in render().
        addHeaderLinkButtons();

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
            safeAdd("refreshCache", () -> this.addDrawableChild(Button.builder(
                    Component.literal("Refresh Cache"),
                    b -> { try { TierTaggerCore.cache().invalidate(); } catch (Throwable ignored) {} })
                .dimensions(colX(0), bottomY, BTN_W, BTN_H).build()));
            safeAdd("done", () -> this.addDrawableChild(Button.builder(
                    Component.literal("Done"), b -> closeSafely())
                .dimensions(colX(1), bottomY, BTN_W, BTN_H).build()));
        } else {
            // Tier Colors / Tierlists — Reset to Default + Done, like the mock-ups.
            final int targetTab = currentTab;
            safeAdd("reset", () -> this.addDrawableChild(Button.builder(
                    Component.literal("Reset to Default"),
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
            safeAdd("done", () -> this.addDrawableChild(Button.builder(
                    Component.literal("Done"), b -> closeSafely())
                .dimensions(colX(1), bottomY, BTN_W, BTN_H).build()));

            // v1.21.11.48: explicit "Scroll to Live Preview" button on the
            // Tiers Config tab. The user reported the preview skin was off-
            // screen and they didn't know they had to mouse-wheel down to
            // reveal it — this button jumps straight to the bottom (where
            // the preview lives) with one click.
            if (targetTab == 2) {
                int scrollBtnSize = BTN_H;
                int scrollBtnX    = colX(1) + BTN_W + 4;
                int maxX = this.width - scrollBtnSize - 4;
                if (scrollBtnX > maxX) scrollBtnX = maxX;
                final int btnX = scrollBtnX;
                safeAdd("scrollToPreview", () -> {
                    Button b = Button.builder(
                            Component.literal("\u25BC"),
                            btn -> {
                                scrollY = maxScroll;
                                scrollByTab[currentTab] = scrollY;
                                rebuildKeepingScroll();
                            })
                        .dimensions(btnX, bottomY, scrollBtnSize, scrollBtnSize)
                        .build();
                    addTipped(b, "Scroll down to the Live Preview\n" +
                            "(shows your current Minecraft skin with the badge format applied).");
                    this.addDrawableChild(b);
                });
            }
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
        for (net.minecraft.client.gui.components.events.GuiEventListener el : this.children()) {
            if (!(el instanceof AbstractWidget cw)) continue;
            int wy = cw.getY();
            // Pinned widgets (header link buttons) live OUTSIDE the body
            // viewport by design — they sit in the title strip at y=12
            // which is well above bodyTop. Without this exemption the
            // strict containment check below sets them visible=false
            // AND active=false, so the user sees the brand-coloured square
            // (drawn manually in render()) but every click is dropped.
            if (pinnedWidgets.contains(cw)) {
                cw.visible = true;
                cw.active  = true;
                continue;
            }
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
            AbstractWidget w = CycleButton.<TierService>builder(
                    s -> Component.literal(s.displayName).withColor(rgb(s.accentArgb)),
                    cfg.primaryServiceEnum())
                .values(TierService.values())
                .build(colX(0), rowY(rRef[0]), BTN_W, BTN_H,
                    Component.literal("Primary Service"),
                    (b, v) -> {
                        cfg.primaryService = v.id;
                        // Reset displayMode if it isn't valid for the new
                        // primary service. "highest" always stays valid.
                        if (cfg.displayMode != null
                            && !cfg.displayMode.equalsIgnoreCase("highest")
                            && !v.modes.contains(cfg.displayMode.toLowerCase(Locale.ROOT))) {
                            cfg.displayMode = "highest";
                        }
                        cfg.save();
                        // Rebuild so Display Mode dropdown re-populates with
                        // the new service's modes.
                        rebuildKeepingScroll();
                    });
            addTipped(w, "Which tierlist drives the single-tier helper " +
                "(used by /tiertagger and the active service display).");
        });
        safeAdd("displayMode", () -> {
            // Only show modes belonging to the PRIMARY service (per user
            // request — Display Mode used to list every gamemode of every
            // tierlist, which polluted the dropdown with modes that the
            // primary service didn't even support). "Dia 2v2" is filtered
            // out everywhere it would surface, including here.
            TierService primSvc = cfg.primaryServiceEnum();
            List<String> modes = new ArrayList<>();
            modes.add("highest");
            if (primSvc != null) {
                for (String m : primSvc.modes) {
                    if (m == null) continue;
                    String lm = m.toLowerCase(Locale.ROOT);
                    if (lm.equals("dia_2v2") || lm.equals("dia2v2") || lm.equals("2v2")) continue;
                    if (!modes.contains(lm)) modes.add(lm);
                }
            }
            String initial = cfg.displayMode == null ? "highest" : cfg.displayMode.toLowerCase(Locale.ROOT);
            if (!modes.contains(initial)) initial = "highest";
            final String initialFinal = initial;
            AbstractWidget w = CycleButton.<String>builder(s -> Component.literal(prettyMode(s)), initialFinal)
                    .values(modes)
                    .build(colX(1), rowY(rRef[0]), BTN_W, BTN_H,
                        Component.literal("Display Mode"),
                        (b, v) -> { cfg.displayMode = v; cfg.save(); });
            addTipped(w, "Choose which gamemode tier is shown by the primary service. " +
                "Only the primary service's gamemodes are listed. " +
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
            AbstractWidget w = CycleButton.<String>builder(s -> Component.literal(prettyMode(s)), initial)
                    .values(modes)
                    .build(colX(0), rowY(rRef[0]), BTN_W, BTN_H,
                        Component.literal("Left Mode"),
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
            AbstractWidget w = CycleButton.<String>builder(s -> Component.literal(prettyMode(s)), initial)
                    .values(modes)
                    .build(colX(1), rowY(rRef[0]), BTN_W, BTN_H,
                        Component.literal("Right Mode"),
                        (b, v) -> { cfg.rightMode = v; cfg.save(); });
            addTipped(w, "Which gamemode the RIGHT badge reads its tier from.");
        });
        rRef[0]++;

        addSectionHeader(rRef[0], "\u2014 Where to Show \u2014");
        rRef[0]++;
        safeAdd("showInTab", () -> {
            AbstractWidget w = CycleButton.onOffBuilder(cfg.showInTab)
                .build(colX(0), rowY(rRef[0]), BTN_W, BTN_H, Component.literal("Tab Badges"),
                    (b, v) -> { cfg.showInTab = v; cfg.save(); });
            addTipped(w, "Show tier badges next to player names in the tab list (TAB key).");
        });
        safeAdd("showNametag", () -> {
            AbstractWidget w = CycleButton.onOffBuilder(cfg.showNametag)
                .build(colX(1), rowY(rRef[0]), BTN_W, BTN_H, Component.literal("Nametag (F5)"),
                    (b, v) -> { cfg.showNametag = v; cfg.save(); });
            addTipped(w, "Show tier badges above players' heads in the world (third-person view).");
        });
        rRef[0]++;

        safeAdd("rightBadgeEnabled", () -> {
            AbstractWidget w = CycleButton.onOffBuilder(cfg.rightBadgeEnabled)
                .build(colX(0), rowY(rRef[0]), BTN_W, BTN_H, Component.literal("Dual Badges"),
                    (b, v) -> { cfg.rightBadgeEnabled = v; cfg.save(); });
            addTipped(w, "Show two tier badges (one for each side's tierlist). When OFF, only the LEFT badge is shown.");
        });
        safeAdd("showPeak", () -> {
            AbstractWidget w = CycleButton.onOffBuilder(cfg.showPeak)
                .build(colX(1), rowY(rRef[0]), BTN_W, BTN_H, Component.literal("Use Peak Tier"),
                    (b, v) -> { cfg.showPeak = v; cfg.save(); });
            addTipped(w, "Show each player's all-time PEAK tier instead of their current tier.");
        });
        rRef[0]++;

        addSectionHeader(rRef[0], "\u2014 Appearance \u2014");
        rRef[0]++;
        safeAdd("coloredBadges", () -> {
            AbstractWidget w = CycleButton.onOffBuilder(cfg.coloredBadges)
                .build(colX(0), rowY(rRef[0]), BTN_W, BTN_H, Component.literal("Coloured Badges"),
                    (b, v) -> { cfg.coloredBadges = v; cfg.save(); });
            addTipped(w, "Colour the tier text (HT1 = gold, HT2 = silver, …). When OFF, badges are plain white.");
        });
        safeAdd("badgeFormat", () -> {
            List<String> formats = Arrays.asList(TierConfig.BADGE_FORMATS);
            String initialFormat = (cfg.badgeFormat == null || !formats.contains(cfg.badgeFormat))
                ? "bracket" : cfg.badgeFormat;
            AbstractWidget w = CycleButton.<String>builder(Component::literal, initialFormat)
                    .values(formats)
                    .build(colX(1), rowY(rRef[0]), BTN_W, BTN_H,
                        Component.literal("Badge Format"),
                        (b, v) -> { cfg.badgeFormat = v; cfg.save(); });
            addTipped(w, "How the tier label is wrapped: bracket = [HT1], plain = HT1, short = HT1.");
        });
        rRef[0]++;

        safeAdd("showServiceIcon", () -> {
            AbstractWidget w = CycleButton.onOffBuilder(cfg.showServiceIcon)
                .build(colX(0), rowY(rRef[0]), BTN_W, BTN_H, Component.literal("Service Tag"),
                    (b, v) -> { cfg.showServiceIcon = v; cfg.save(); });
            addTipped(w, "Prefix each badge with the tierlist's short name (OT, MC, PVP, SUB).");
        });
        safeAdd("modeIcons", () -> {
            AbstractWidget w = CycleButton.onOffBuilder(!cfg.disableIcons && cfg.showModeIcon)
                .build(colX(1), rowY(rRef[0]), BTN_W, BTN_H, Component.literal("Gamemode Icons"),
                    (b, v) -> { cfg.disableIcons = !v; cfg.showModeIcon = v; cfg.save(); });
            addTipped(w, "Show the gamemode icon (sword, axe, …) next to each tier on the profile screens.");
        });
        rRef[0]++;

        safeAdd("disableTiers", () -> {
            AbstractWidget w = CycleButton.onOffBuilder(!cfg.disableTiers)
                .build(colX(0), rowY(rRef[0]), BTN_W, BTN_H, Component.literal("Show Tier Text"),
                    (b, v) -> { cfg.disableTiers = !v; cfg.save(); });
            addTipped(w, "Master switch — when OFF, no tier badges appear anywhere.");
        });
        safeAdd("disableAnimations", () -> {
            AbstractWidget w = CycleButton.onOffBuilder(!cfg.disableAnimations)
                .build(colX(1), rowY(rRef[0]), BTN_W, BTN_H, Component.literal("Animations"),
                    (b, v) -> { cfg.disableAnimations = !v; cfg.save(); });
            addTipped(w, "Enable subtle fade / pulse animations on the badges.");
        });
        rRef[0]++;

        safeAdd("fallthrough", () -> {
            AbstractWidget w = CycleButton.onOffBuilder(cfg.fallthroughToHighest)
                .build(colX(0), rowY(rRef[0]), BTN_W, BTN_H, Component.literal("Fallback to Highest"),
                    (b, v) -> { cfg.fallthroughToHighest = v; cfg.save(); });
            addTipped(w, "If a player has no tier in the selected gamemode, fall back to their highest tier.");
        });
        safeAdd("ttl", () -> {
            List<Integer> ttls = Arrays.asList(60, 180, 300, 600, 1800, 3600);
            int initial = ttls.contains(cfg.cacheTtlSeconds) ? cfg.cacheTtlSeconds : 300;
            AbstractWidget w = CycleButton.<Integer>builder(s -> Component.literal(prettyTtl(s)), initial)
                    .values(ttls)
                    .build(colX(1), rowY(rRef[0]), BTN_W, BTN_H,
                        Component.literal("Cache TTL"),
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
                AbstractWidget w = CycleButton.onOffBuilder(cfg.isServiceEnabled(svc))
                    .build(colX(col), rowY(rRef[0]), BTN_W, BTN_H,
                        Component.literal(svc.displayName).withColor(rgb(svc.accentArgb)),
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
            AbstractWidget w = Button.builder(
                    Component.literal("Tab Modes\u2026"),
                    b -> {
                        Minecraft mc = this.client != null ? this.client : Minecraft.getInstance();
                        if (mc != null) mc.setScreen(new ModeFilterScreen(this, true));
                    })
                .dimensions(colX(0), rowY(rRef[0]), BTN_W, BTN_H).build();
            addTipped(w, "Pick which gamemodes contribute to the badge shown in the tab list.");
        });
        safeAdd("nametagModes", () -> {
            AbstractWidget w = Button.builder(
                    Component.literal("Nametag Modes\u2026"),
                    b -> {
                        Minecraft mc = this.client != null ? this.client : Minecraft.getInstance();
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
        final EditBox field;
        ColorRow(String t, EditBox f) { this.tierKey = t; this.field = f; }
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
                EditBox f = new EditBox(
                    this.textRenderer,
                    rowX(), rowY(row), fieldW, BTN_H,
                    Component.literal(key));
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
            AbstractWidget w = CycleButton
                .onOffBuilder(!cfg.disableTiers)
                .build(rowX(), row1y, rowW(), BTN_H,
                    Component.literal("Disable Tiers"),
                    (b, v) -> { cfg.disableTiers = !v; cfg.save(); });
            addTipped(w, "Toggle the entire tier display. When OFF, no tier badges appear anywhere.");
        });
        rRef[0]++;

        // ── Row 2: Enable Dynamic Separator (full width) ──────────────────
        final int row2y = rowY(rRef[0]);
        safeAdd("dynamicSeparator", () -> {
            AbstractWidget w = CycleButton
                .onOffBuilder(cfg.dynamicSeparator)
                .build(rowX(), row2y, rowW(), BTN_H,
                    Component.literal("Enable Dynamic Separator"),
                    (b, v) -> { cfg.dynamicSeparator = v; cfg.save(); });
            addTipped(w, "Make the Tiers separator color match the tier color.");
        });
        rRef[0]++;

        // ── Row 3: Displayed Tiers (cycling Selected/Highest/Adaptive) ────
        final int row3y = rowY(rRef[0]);
        safeAdd("displayedTiers", () -> {
            String initial = cfg.displayedTiers == null ? "adaptive_highest" : cfg.displayedTiers;
            AbstractWidget w = CycleButton
                .<String>builder(s -> Component.literal(TierConfig.displayedTiersLabel(s)), initial)
                .values(Arrays.asList(TierConfig.DISPLAYED_TIER_MODES))
                .build(rowX(), row3y, rowW(), BTN_H,
                    Component.literal("Displayed Tiers"),
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
            AbstractWidget w = CycleButton
                .onOffBuilder(cfg.autoKitDetect)
                .build(rowX(), row4y, rowW(), BTN_H,
                    Component.literal("Enable auto kit detect"),
                    (b, v) -> { cfg.autoKitDetect = v; cfg.save(); });
            addTipped(w,
                "Tiers will always scan your inventory to display the right gamemode " +
                "(instead of having to press 'I').");
        });
        rRef[0]++;

        // ── Row 4b: Cycle-mode keybind PICKER (click-to-bind) ─────────────
        // The user clicks the button, the screen enters "listening" mode,
        // and the next physical key the user presses becomes the new bind.
        // Press ESC to cancel; the existing bind is kept. The change is
        // persisted to Minecraft's options.txt automatically by
        // KeyMapping#setBoundKey (see TierKeybinds).
        final int rowKeyY = rowY(rRef[0]);
        safeAdd("cycleKey", () -> {
            Button btn = Button.builder(
                    Component.literal(cycleKeyLabel()),
                    b -> {
                        // Toggle listening mode — KeyboardMixin reads
                        // CAPTURING_CYCLE_KEY to intercept the next key.
                        CAPTURING_CYCLE_KEY = !CAPTURING_CYCLE_KEY;
                        refreshCycleKeyButtonLabel();
                    })
                .dimensions(rowX(), rowKeyY, rowW(), BTN_H)
                .build();
            CYCLE_KEY_BUTTON = btn;
            addTipped(btn,
                "Click the button, then press any key to bind it as the " +
                "cycle-mode key (cycles the right-side gamemode in-game). " +
                "Press ESC to cancel. You can also rebind this in Options " +
                "\u2192 Controls \u2192 TierTagger.");
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

        // ── Image-icon buttons (v1.21.11.35) ─────────────────────────────
        // The previous implementation rendered Unicode glyphs (\u21BB / \u25C9
        // / \u26ED / \u2630 / \u270E). On many resource packs and shaders
        // those rendered as fuzzy squares or "tofu". We now use vanilla
        // item icons drawn through the item renderer (see ItemIconButton),
        // which stay crisp at any GUI scale and read instantly. Toggle
        // buttons get a translucent overlay + red diagonal slash when OFF
        // so users can tell their state at a glance.

        // [Clock] Reload tier cache.
        safeAdd("reloadCache", () -> {
            int x = rowX();
            Button btn = Button.builder(Component.empty(),
                    b -> { try { TierTaggerCore.cache().invalidate(); } catch (Throwable ignored) {} })
                .dimensions(x, iconRowY, iconBtnW, iconBtnH).build();
            iconOverlays.add(new Object[]{ btn, new ItemStack(Items.CLOCK), null });
            addTipped(btn, "Reload the tier cache.");
        });

        // [Ender Eye] Cycle active right gamemode (in-game "I" hotkey).
        safeAdd("cycleRightMode", () -> {
            int x = rowX() + (iconBtnW + iconGap);
            Button btn = Button.builder(Component.empty(),
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
                .dimensions(x, iconRowY, iconBtnW, iconBtnH).build();
            iconOverlays.add(new Object[]{ btn, new ItemStack(Items.ENDER_EYE), null });
            addTipped(btn, "Cycle the active right gamemode (press 'I' in game).");
        });

        // [Iron Sword] Gamemode-icon visibility toggle.
        safeAdd("toggleGameModeIcon", () -> {
            int x = rowX() + 2 * (iconBtnW + iconGap);
            Button btn = Button.builder(Component.empty(),
                    b -> {
                        boolean nowOn = !(!cfg.disableIcons && cfg.showModeIcon);
                        cfg.disableIcons = !nowOn;
                        cfg.showModeIcon = nowOn;
                        cfg.save();
                        rebuildKeepingScroll();
                    })
                .dimensions(x, iconRowY, iconBtnW, iconBtnH).build();
            BooleanSupplier dim = () -> cfg.disableIcons || !cfg.showModeIcon;
            iconOverlays.add(new Object[]{ btn, new ItemStack(Items.IRON_SWORD), dim });
            addTipped(btn, "Show / hide the small gamemode icon next to the tier.");
        });

        // [Paper] Tab-list visibility toggle.
        safeAdd("toggleTablist", () -> {
            int x = rowX() + 3 * (iconBtnW + iconGap);
            Button btn = Button.builder(Component.empty(),
                    b -> {
                        cfg.showInTab = !cfg.showInTab;
                        cfg.save();
                        rebuildKeepingScroll();
                    })
                .dimensions(x, iconRowY, iconBtnW, iconBtnH).build();
            BooleanSupplier dim = () -> !cfg.showInTab;
            iconOverlays.add(new Object[]{ btn, new ItemStack(Items.PAPER), dim });
            addTipped(btn, "Show / hide Tiers on the tab list.");
        });

        // [Writable Book] Chat visibility toggle.
        safeAdd("toggleChat", () -> {
            int x = rowX() + 4 * (iconBtnW + iconGap);
            Button btn = Button.builder(Component.empty(),
                    b -> {
                        cfg.disableInChat = !cfg.disableInChat;
                        cfg.save();
                        rebuildKeepingScroll();
                    })
                .dimensions(x, iconRowY, iconBtnW, iconBtnH).build();
            BooleanSupplier dim = () -> cfg.disableInChat;
            iconOverlays.add(new Object[]{ btn, new ItemStack(Items.WRITABLE_BOOK), dim });
            addTipped(btn, "Show / hide Tiers in chat.");
        });

        // The icon row uses iconBtnH (28 px) which is taller than ROW_H (24 px),
        // so consume an extra rRef++ to give the next widget vertical clearance.
        rRef[0]++;
        rRef[0]++;

        // ── Advanced Settings (renamed from "Advanced — Left/Right Routing…") ─
        rRef[0]++; // breathing room
        final int advY = rowY(rRef[0]);
        safeAdd("advanced", () -> {
            AbstractWidget w = Button.builder(
                    Component.literal("Advanced Settings"),
                    b -> openAdvancedRouting(cfg))
                .dimensions(rowX(), advY, rowW(), BTN_H)
                .build();
            addTipped(w,
                "Open the per-service Left / Right tierlist picker. " +
                "Use this if you want PvPTiers on one side and OuterTiers on the other.");
        });
        rRef[0]++;
        rRef[0]++; // breathing room before preview

        // ── Live nametag preview (v1.21.11.45) ────────────────────────────
        // Anchored below "Advanced Settings". Re-renders on every settings
        // change because rebuildKeepingScroll() runs at the end of every
        // toggle handler — so the badge format, brackets, icon position,
        // service colours etc. all update in real time.
        // v1.21.11.45: previewH bumped from 170 → 280 so the full-body
        // Outversal render (head, torso, arms, LEGS) fits inside the card
        // at a meaningful size. Scroll reservation widened to 13 rows so
        // the user can scroll all the way down to see the feet.
        previewX = rowX();
        previewY = rowY(rRef[0]);
        previewW = rowW();
        previewH = 280;
        rRef[0] += 13; // reserve scroll space for the taller preview
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
            AbstractWidget w = Button.builder(
                    Component.literal("\u00a7e\u2190 Back"),
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
            AbstractWidget w = Button.builder(
                    Component.literal(leftLabel).withColor(leftColor), b -> {})
                .dimensions(colX(0), rowY(rStatus), BTN_W, BTN_H).build();
            addTipped(w, "Currently displayed on the LEFT side of player names.");
        });
        safeAdd("statusRight", () -> {
            AbstractWidget w = Button.builder(
                    Component.literal(rightLabel).withColor(rightColor), b -> {})
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
                AbstractWidget w = Button.builder(
                        Component.literal(isLeft ? "[\u2190]" : "\u2190"),
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
                AbstractWidget w = Button.builder(
                        Component.literal(labelText).withColor(rgb(svc.accentArgb)),
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
                AbstractWidget w = Button.builder(
                        Component.literal(isRight ? "[\u2192]" : "\u2192"),
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

        // ── Live nametag preview (v1.21.11.45) ────────────────────────────
        // Anchored below the SubTiers / services list inside the Advanced
        // Settings sub-screen. Updates in real time when the user clicks
        // [←] / [→] on any row because each handler calls
        // rebuildKeepingScroll() which re-runs this builder.
        // v1.21.11.45: matches the main tab — taller box + more scroll
        // room so the full Outversal body is visible head-to-toe.
        rRef[0]++; // breathing room
        previewX = rowX();
        previewY = rowY(rRef[0]);
        previewW = rowW();
        previewH = 280;
        rRef[0] += 13;
    }

    /**
     * Build (once, lazily) the fake {@link PlayerData} used by the live
     * preview. Populates an HT-{1..3} ranking for every gamemode in every
     * known service so that no matter which left/right service or mode
     * the user picks the badge has something to show.
     */
    private static PlayerData ensurePreviewData() {
        String currentName = previewName();
        PlayerData cached = PREVIEW_DATA;
        if (cached != null && currentName.equals(LAST_PREVIEW_NAME)) return cached;
        synchronized (TierConfigScreen.class) {
            if (PREVIEW_DATA != null && currentName.equals(LAST_PREVIEW_NAME)) return PREVIEW_DATA;
            try {
                PlayerData pd = new PlayerData(currentName,
                        // Notch's canonical undashed UUID. Used only as a
                        // stable cache key — the screen itself fetches the
                        // skin via SkinFetcher.skinFor("Notch").
                        "069a79f444e94726a5befca90e38aaf5");
                int seed = 1;
                for (TierService svc : TierService.values()) {
                    LinkedHashMap<String, Ranking> map = new LinkedHashMap<>();
                    for (String mode : svc.modes) {
                        // Cycle through HT1, HT2, HT3, LT2, HT1, … so the
                        // preview shows a believable spread of tiers.
                        int level = 1 + (seed % 3);
                        boolean high = (seed % 4) != 0;
                        map.put(mode, Ranking.simple(level, high));
                        seed++;
                    }
                    pd.services.put(svc, new ServiceData(
                            svc, map, "EU", 1234, 42,
                            System.currentTimeMillis(), false));
                }
                PREVIEW_DATA = pd;
                LAST_PREVIEW_NAME = currentName;
                return pd;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /**
     * Render the live nametag preview panel. v1.21.11.37 redesign: shows
     * the FULL-body Outversal render (head-to-toe, ≈1:2.4 aspect) centered
     * inside the card with the live-formatted nametag floating directly
     * above the head — exactly like an in-game player nametag. Visual only,
     * does NOT register a widget so it can't eat clicks. Bounds are set
     * during {@link #buildTiersConfigTab} (or the advanced routing view).
     */
    private void drawPreview(GuiGraphics ctx) {
        if (previewW <= 0 || previewH <= 0) return;
        // Don't render if scrolled outside the visible body area.
        if (previewY + previewH < bodyTop || previewY > bodyBottom) return;
        if (currentTab != 2) return;

        TierConfig cfg = TierTaggerCore.config();
        PlayerData pd = ensurePreviewData();
        if (pd == null) return;

        // ── Animation timebase (v1.21.11.45) ──────────────────────────────
        // Single shared time value drives every animated element below so
        // the breathing accent, glow pulse and skin sway all stay in
        // perfect phase. Util.getMeasuringTimeMs() is the same monotonic
        // clock vanilla uses for HUD pulses.
        long now = Util.getMeasuringTimeMs();
        double tSec  = now / 1000.0;
        // 0..1 sinusoidal pulse, ~3.6s period — slow enough to feel calm,
        // fast enough to be visibly alive.
        double pulse = 0.5 + 0.5 * Math.sin(tSec * (2.0 * Math.PI / 3.6));
        // Asymmetric pulse used for the accent stripe glow — slightly
        // out-of-phase with the main pulse to avoid a robotic in/out
        // metronome feel.
        double glow  = 0.5 + 0.5 * Math.sin(tSec * (2.0 * Math.PI / 5.2) + 1.1);

        ctx.enableScissor(0, bodyTop, this.width, bodyBottom);
        try {
            // Card background with subtle gradient + accent border.
            verticalGradient(ctx, previewX, previewY,
                    previewX + previewW, previewY + previewH,
                    0xFF14181F, 0xFF0B0E13);
            outlineRect(ctx, previewX, previewY, previewW, previewH, 0xFF2C313A);

            // ── Animated yellow accent stripe ────────────────────────────
            // The 2px solid stripe is now flanked by a soft glow that
            // breathes in/out (alpha range 0x18 → 0x60). Drawn as three
            // 1-pixel half-transparent columns to fake a smooth bloom
            // without needing custom shaders.
            int glowAlpha = 0x18 + (int) Math.round(glow * (0x60 - 0x18));
            int glowColor = (glowAlpha << 24) | (ACCENT & 0x00FFFFFF);
            fillRect(ctx, previewX + 2, previewY, previewX + 3, previewY + previewH, glowColor);
            fillRect(ctx, previewX + 3, previewY, previewX + 4, previewY + previewH,
                    ((glowAlpha / 2) << 24) | (ACCENT & 0x00FFFFFF));
            fillRect(ctx, previewX, previewY, previewX + 2, previewY + previewH, ACCENT);

            // "LIVE PREVIEW" caption with a tiny live-dot indicator that
            // pulses red→bright-red so the user knows the panel is hot.
            Component caption = Component.literal("LIVE PREVIEW").formatted(ChatFormatting.GRAY, ChatFormatting.BOLD);
            int dotR = 0xFF + 0; // base red channel always max
            int dotPulse = 0x80 + (int) Math.round(pulse * 0x60);
            int dotColor = (0xFF << 24) | (dotR << 16) | (dotPulse / 4 << 8) | (dotPulse / 4);
            fillRect(ctx, previewX + 8, previewY + 8, previewX + 12, previewY + 12, dotColor);
            ctx.drawTextWithShadow(this.textRenderer, caption,
                    previewX + 16, previewY + 4, 0xFF8A8F99);

            // Build the live wrapped nametag — same renderer that produces
            // real in-game nametags, so the preview matches what other
            // players see.
            MutableComponent baseName = Component.literal(previewName()).formatted(ChatFormatting.WHITE);
            Component wrapped;
            try {
                Component wt = BadgeRenderer.wrapNametag(cfg, pd, baseName);
                wrapped = wt != null ? wt : baseName;
            } catch (Throwable t) {
                wrapped = baseName;
            }

            // Reserve room at the top for the caption + the floating
            // nametag. The skin slot fills the remaining vertical space and
            // is centered horizontally inside the card.
            // v1.21.11.45: with previewH = 280 and a 1:2.4 body aspect, a
            // slot of 130×~250 lets the figure render at ≈ 105×250, so the
            // legs are FULLY visible instead of being clipped under the
            // bottom edge of the card.
            int topPad   = 18;        // caption row
            int tagBoxH  = 12;
            int bottomPad = 8;
            int slotTop = previewY + topPad + tagBoxH + 4;
            int slotBot = previewY + previewH - bottomPad;
            int slotH   = Math.max(40, slotBot - slotTop);
            // Use a tighter horizontal slot so vertical fit-inside scaling
            // wins on the 1:2.4 body aspect — the figure becomes tall and
            // thin (fully visible head→feet) instead of squat and clipped.
            int slotW   = Math.max(56, Math.min(previewW - 16, slotH * 5 / 11));
            int slotX   = previewX + (previewW - slotW) / 2;
            int slotY   = slotTop;

            // Subtle horizontal sway (±1 px) gives the rendered figure a
            // tiny "breathing" idle motion. Using floor() so we always end
            // on a whole pixel — fractional offsets would smear the
            // texture's hard pixel edges.
            int sway = (int) Math.floor(Math.sin(tSec * (2.0 * Math.PI / 4.0)) * 1.0);

            // Draw the full-body Outversal skin — head, chest, arms, legs.
            // Anchor it to the bottom of the slot so the feet align to the
            // bottom edge (matches the OuterTiers website player cards).
            drawPreviewBody(ctx, slotX + sway, slotY, slotW, slotH);

            // Floating nametag — vanilla look (25%-alpha black background,
            // white text). Positioned right above the head of the rendered
            // skin so the visual association is unmistakable. The
            // background alpha now breathes between 0x40 and 0x70 in time
            // with the pulse so the badge feels live.
            int tagW    = this.textRenderer.getWidth(wrapped);
            int tagPad  = 4;
            int tagBoxW = tagW + tagPad * 2;
            int tagBoxX = previewX + (previewW - tagBoxW) / 2;
            int tagBoxY = slotY - tagBoxH - 2;
            int bgAlpha = 0x40 + (int) Math.round(pulse * (0x70 - 0x40));
            int bgColor = (bgAlpha << 24);
            fillRect(ctx, tagBoxX, tagBoxY, tagBoxX + tagBoxW, tagBoxY + tagBoxH, bgColor);
            // 1-pixel accent-coloured underline beneath the floating tag,
            // alpha-pulsing in phase with the bg. Helps connect the
            // nametag visually to the accent stripe on the left edge.
            int uline = ((0x60 + (int) Math.round(pulse * 0x60)) << 24)
                       | (ACCENT & 0x00FFFFFF);
            fillRect(ctx, tagBoxX, tagBoxY + tagBoxH - 1,
                    tagBoxX + tagBoxW, tagBoxY + tagBoxH, uline);
            ctx.drawTextWithShadow(this.textRenderer, wrapped,
                    tagBoxX + tagPad, tagBoxY + 2, 0xFFFFFFFF);
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] preview render", t);
        } finally {
            try { ctx.disableScissor(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Draws the FULL body of the preview player's skin (head to toe),
     * scaled to fit inside {@code (boxW, boxH)} while preserving the
     * natural ≈1:2.4 aspect of mc-heads.net's /body/ render. Anchored to
     * the BOTTOM of the slot so the feet always touch the bottom edge.
     * Falls back to a soft Steve-coloured silhouette while the skin is
     * still being fetched.
     */
    private void drawPreviewBody(GuiGraphics ctx, int x, int y, int boxW, int boxH) {
        Optional<SkinFetcher.Skin> fetched = Optional.empty();
        try { fetched = SkinFetcher.skinFor(previewName()); } catch (Throwable ignored) {}
        if (fetched.isPresent()) {
            try {
                SkinFetcher.Skin sk = fetched.get();
                int iw = Math.max(1, sk.width);
                int ih = Math.max(1, sk.height);
                // Fit-inside scaling using the FULL image (no UV crop).
                double sx = (double) boxW / iw;
                double sy = (double) boxH / ih;
                double scale = Math.min(sx, sy);
                int dw = Math.max(1, (int) Math.floor(iw * scale));
                int dh = Math.max(1, (int) Math.floor(ih * scale));
                int dx = x + (boxW - dw) / 2;
                int dy = y + (boxH - dh);   // anchor to bottom of slot
                Compat.drawTexture(ctx, sk.id, dx, dy, 0, 0, dw, dh, dw, dh);
                return;
            } catch (Throwable ignored) {}
        }
        // Loading placeholder — Steve-coloured head + torso + legs anchored
        // to the bottom so the layout matches the eventual real render.
        int cx = x + boxW / 2;
        int feetY = y + boxH;
        int legH   = Math.max(8, boxH * 5 / 16);
        int torsoH = Math.max(10, boxH * 6 / 16);
        int headH  = Math.max(8, boxH * 4 / 16);
        int torsoBot = feetY - legH;
        int torsoTop = torsoBot - torsoH;
        int headBot  = torsoTop;
        int headTop  = headBot - headH;
        // Legs
        fillRect(ctx, cx - 4, torsoBot, cx,     feetY,    0xFF1E2A45);
        fillRect(ctx, cx,     torsoBot, cx + 4, feetY,    0xFF1E2A45);
        // Torso
        fillRect(ctx, cx - 6, torsoTop, cx + 6, torsoBot, 0xFF4A6FA5);
        // Arms
        fillRect(ctx, cx - 9, torsoTop, cx - 6, torsoBot, 0xFFB78462);
        fillRect(ctx, cx + 6, torsoTop, cx + 9, torsoBot, 0xFFB78462);
        // Head
        fillRect(ctx, cx - 4, headTop,  cx + 4, headBot,  0xFFB78462);
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
                    Component.literal(label).formatted(ChatFormatting.YELLOW),
                    this.width / 2, y, FG_SECTION);
            }
            ctx.disableScissor();

            // 3. Widgets rendered WITHOUT scissor so the bottom action row is
            //    never clipped. Anything that leaks above the body is repainted
            //    by the title strip / tab strip below.
            super.render(ctx, mouseX, mouseY, delta);

            // 3a. Image-icon overlays for the 5 action buttons (reload /
            //     cycle / G / T / C). The buttons themselves render with an
            //     empty label; we paint the vanilla item icon centered over
            //     the button face here, then overlay a translucent dimmer
            //     + red diagonal slash for OFF toggles. This sidesteps the
            //     PressableWidget#drawIcon abstract-method requirement and
            //     the Button$Text nested-type shadowing issues that
            //     made a clean Button subclass impractical across MC
            //     1.21.5+ Yarn mappings.
            try {
                for (Object[] oo : iconOverlays) {
                    Button btn = (Button) oo[0];
                    ItemStack stack = (ItemStack) oo[1];
                    BooleanSupplier dim = (BooleanSupplier) oo[2];
                    if (btn == null || stack == null || stack.isEmpty()) continue;
                    int ix = btn.getX() + (btn.getWidth()  - 16) / 2;
                    int iy = btn.getY() + (btn.getHeight() - 16) / 2;
                    ctx.drawItem(stack, ix, iy);
                    if (dim != null && dim.getAsBoolean()) {
                        // Translucent grey wash over the icon.
                        ctx.fill(btn.getX() + 1, btn.getY() + 1,
                                 btn.getX() + btn.getWidth()  - 1,
                                 btn.getY() + btn.getHeight() - 1,
                                 0xA00C0F14);
                        // Diagonal red "/" so the OFF state reads at a glance.
                        int x1 = btn.getX() + 4;
                        int y1 = btn.getY() + btn.getHeight() - 5;
                        int x2 = btn.getX() + btn.getWidth() - 4;
                        int y2 = btn.getY() + 4;
                        drawDiagonalSlash(ctx, x1, y1, x2, y2, 0xFFE74C3C);
                    }
                }
            } catch (Throwable ignored) {}

            // 3a-bis. Animated pulsing accent ring around the Cycle Mode Key
            //         button while we're WAITING for the user to press a key.
            //         Gives the same feel as the vanilla Controls screen's
            //         "press a key" prompt (subtle, never distracting).
            try {
                if (CAPTURING_CYCLE_KEY && CYCLE_KEY_BUTTON != null) {
                    int bx = CYCLE_KEY_BUTTON.getX();
                    int by = CYCLE_KEY_BUTTON.getY();
                    int bw = CYCLE_KEY_BUTTON.getWidth();
                    int bh = CYCLE_KEY_BUTTON.getHeight();
                    // Only draw when visible inside the body band so it
                    // never bleeds into the title / tab strips.
                    if (by + bh > bodyTop && by < bodyBottom) {
                        // 0..1..0 sine pulse at ~1.5 Hz.
                        double t = (System.currentTimeMillis() % 1333L) / 1333.0;
                        float pulse = 0.45f + 0.55f * (float) Math.abs(Math.sin(t * Math.PI));
                        int alpha = Math.min(255, Math.round(pulse * 220f));
                        int ringArgb = (alpha << 24) | (ACCENT & 0xFFFFFF);
                        // Outer glow (2 px, fading) + inner crisp 1 px ring.
                        for (int i = 1; i <= 2; i++) {
                            int a = Math.max(0, alpha - i * 70);
                            int gArgb = (a << 24) | (ACCENT & 0xFFFFFF);
                            outlineRect(ctx, bx - i, by - i, bw + i * 2, bh + i * 2, gArgb);
                        }
                        outlineRect(ctx, bx, by, bw, bh, ringArgb);
                    }
                }
            } catch (Throwable ignored) {}

            // 3b. Live nametag preview (Tiers Config tab only). Drawn AFTER
            //     widgets so its translucent backdrop sits on top of any
            //     buttons whose body region overlaps the preview slot.
            try { drawPreview(ctx); } catch (Throwable ignored) {}

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
                        Component.literal(cr.tierKey).formatted(ChatFormatting.GRAY),
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

            // Header layout (v1.21.11.37): OuterTiers logo + bold
            // "TierTagger" wordmark on the LEFT; the three square link
            // buttons (Discord / OuterTiers / Linktree) live on the
            // RIGHT and are painted further down on top of their hidden
            // Button hit-rects.
            int logoSize = 16;
            int logoX = panelX + 8;
            int logoY = panelTop + 4;
            // Subtle dark square behind the logo so it reads on any
            // gradient — and a fallback "OT" monogram if the bundled
            // texture is missing in this build.
            fillRect(ctx, logoX - 1, logoY - 1, logoX + logoSize + 1, logoY + logoSize + 1, 0xFF0E1218);
            outlineRect(ctx, logoX - 1, logoY - 1, logoSize + 2, logoSize + 2, 0x80FFFFFF);
            boolean drewLogo = false;
            try {
                Compat.drawTexture(ctx, OT_LOGO, logoX, logoY, 0, 0,
                        logoSize, logoSize, logoSize, logoSize);
                drewLogo = true;
            } catch (Throwable ignored) {
                drewLogo = false;
            }
            if (!drewLogo) {
                Component mono = Component.literal("OT").formatted(ChatFormatting.YELLOW, ChatFormatting.BOLD);
                int monoW = this.textRenderer.getWidth(mono);
                ctx.drawTextWithShadow(this.textRenderer, mono,
                        logoX + (logoSize - monoW) / 2, logoY + 4, 0xFFFFFF55);
            }

            // Bold "TierTagger" wordmark right of the logo, plus a small
            // version line below it. Left-aligned because the right side
            // is reserved for the three link buttons.
            int titleTextX = logoX + logoSize + 6;
            ctx.drawTextWithShadow(this.textRenderer,
                    Component.literal("TierTagger").formatted(ChatFormatting.WHITE, ChatFormatting.BOLD),
                    titleTextX, panelTop + 4, 0xFFFFFFFF);
            // v1.21.11.48: append "(update available!)" when the background
            // UpdateChecker has detected a newer release on GitHub. This is
            // the visible-in-UI counterpart to the LOG warning emitted from
            // TierTaggerCore.init() so users running an outdated jar see
            // it the moment they open the settings screen.
            String latestVersion = null;
            try { latestVersion = com.outertiers.tiertagger.common.UpdateChecker.latestVersion(); } catch (Throwable ignored) {}
            boolean outdated = false;
            try { outdated = com.outertiers.tiertagger.common.UpdateChecker.isOutdated(); } catch (Throwable ignored) {}
            String versionLine = "v" + TierTaggerCore.MOD_VERSION + "  \u00B7  /tiertagger help";
            if (outdated && latestVersion != null) {
                versionLine = "v" + TierTaggerCore.MOD_VERSION + "  \u00B7  Update available: v" + latestVersion;
            }
            ctx.drawTextWithShadow(this.textRenderer,
                    Component.literal(versionLine)
                            .withColor(outdated ? 0xFFFF6464 : rgb(FG_FAINT)),
                    titleTextX, panelTop + 15, outdated ? 0xFFFF6464 : FG_FAINT);

            // Paint the three link buttons (Discord, OuterTiers, Linktree)
            // on top of their invisible Button hit-rects so the
            // colour and icon match the brand instead of the vanilla
            // grey-button look. Drawn AFTER super.render() so we cover
            // the default button face.
            for (Object[] hl : headerLinks) {
                Button btn   = (Button) hl[0];
                int          col   = (Integer)      hl[1];
                String       lbl   = (String)       hl[2];
                ResourceLocation   tx    = (ResourceLocation)   hl[3];
                int bx = btn.getX();
                int by = btn.getY();
                int bw = btn.getWidth();
                int bh = btn.getHeight();
                boolean hover = mouseX >= bx && mouseX < bx + bw
                             && mouseY >= by && mouseY < by + bh;
                int face = hover ? lighten(col, 0.18f) : col;
                fillRect(ctx, bx, by, bx + bw, by + bh, face);
                outlineRect(ctx, bx, by, bw, bh, hover ? 0xFFFFFFFF : 0xFF000000);
                boolean drewIcon = false;
                if (tx != null) {
                    try {
                        int pad = 2;
                        Compat.drawTexture(ctx, tx, bx + pad, by + pad, 0, 0,
                                bw - pad * 2, bh - pad * 2,
                                bw - pad * 2, bh - pad * 2);
                        drewIcon = true;
                    } catch (Throwable ignored) {}
                }
                if (!drewIcon) {
                    Component initial = Component.literal(lbl)
                            .formatted(ChatFormatting.WHITE, ChatFormatting.BOLD);
                    int iw = this.textRenderer.getWidth(initial);
                    ctx.drawTextWithShadow(this.textRenderer, initial,
                            bx + (bw - iw) / 2 + 1,
                            by + (bh - 8) / 2,
                            0xFFFFFFFF);
                }
            }

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

            // 7. Scroll indicator. Wider (6 px) and brighter than the
            //    pre-v1.21.11.39 hairline so users actually notice the
            //    Live Preview is below the fold and can be scrolled to.
            if (maxScroll > 0) {
                int trackW = 6;
                int trackX  = panelX + panelW - trackW - 2;
                int trackTop = bodyTop;
                int trackH  = bodyBottom - bodyTop;
                fillRect(ctx, trackX, trackTop, trackX + trackW, trackTop + trackH, 0x60000000);
                outlineRect(ctx, trackX, trackTop, trackW, trackH, 0x80FFFFFF);
                int thumbH = Math.max(24, trackH * trackH / Math.max(1, trackH + maxScroll));
                int thumbY = trackTop + (int)((long)(trackH - thumbH) * scrollY / Math.max(1, maxScroll));
                fillRect(ctx, trackX + 1, thumbY + 1, trackX + trackW - 1, thumbY + thumbH - 1, ACCENT);
                outlineRect(ctx, trackX + 1, thumbY + 1, trackW - 2, thumbH - 2, 0xFFFFFFFF);
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

    private void renderTabStrip(GuiGraphics ctx, int panelX, int panelW) {
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
                Component.literal(TAB_LABELS[i]).formatted(active ? ChatFormatting.WHITE : ChatFormatting.GRAY),
                tx + tabW / 2, ty + (TAB_H - 8) / 2, textColor);
        }
    }

    private void drawErrorOverlay(GuiGraphics ctx, String title, String detail) {
        int cx = this.width / 2;
        int cy = this.height / 2;
        int w  = Math.min(this.width - 40, 360);
        fillRect(ctx, cx - w / 2, cy - 30, cx + w / 2, cy + 30, 0xCC110000);
        outlineRect(ctx, cx - w / 2, cy - 30, w, 60, 0xFFFF5555);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
            Component.literal(title).formatted(ChatFormatting.RED, ChatFormatting.BOLD), cx, cy - 18, 0xFFFF5555);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
            Component.literal(detail).formatted(ChatFormatting.WHITE), cx, cy - 4, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
            Component.literal("See latest.log for the full stack trace").formatted(ChatFormatting.GRAY),
            cx, cy + 12, 0xFFAAAAAA);
    }

    private static void fillRect(GuiGraphics ctx, int x1, int y1, int x2, int y2, int argb) {
        try { ctx.fill(x1, y1, x2, y2, argb); } catch (Throwable ignored) {}
    }

    /**
     * Draws a 1-pixel-wide diagonal line from (x0,y0) to (x1,y1) by stamping
     * single-pixel rects along a Bresenham trace. Used to overlay a red "/"
     * slash on OFF toggle buttons so users can read their state at a glance.
     */
    private static void drawDiagonalSlash(GuiGraphics ctx, int x0, int y0, int x1, int y1, int argb) {
        int dx =  Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int x = x0, y = y0;
        for (int safety = 0; safety < 2048; safety++) {
            try { ctx.fill(x, y, x + 1, y + 1, argb); } catch (Throwable ignored) {}
            if (x == x1 && y == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x += sx; }
            if (e2 <= dx) { err += dx; y += sy; }
        }
    }

    private static void outlineRect(GuiGraphics ctx, int x, int y, int w, int h, int argb) {
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
    private static void verticalGradient(GuiGraphics ctx, int x1, int y1, int x2, int y2,
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
        Minecraft mc = this.client != null ? this.client : Minecraft.getInstance();
        if (mc != null) mc.setScreen(parent);
    }

    @Override
    public void close() { closeSafely(); }

    @SuppressWarnings("unused")
    private static final Set<String> _USED = new LinkedHashSet<>();
}

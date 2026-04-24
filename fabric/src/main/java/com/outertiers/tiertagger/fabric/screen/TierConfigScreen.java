package com.outertiers.tiertagger.fabric.screen;

import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierIcons;
import com.outertiers.tiertagger.common.TierService;
import com.outertiers.tiertagger.common.TierTaggerCore;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * MCTiers-style config screen. Three logical sections:
 *
 *   1) Mode grid — checkboxes for each gamemode (with vanilla item icons).
 *   2) Service column — per-service enable + left/right badge selectors.
 *   3) Toggle row — global cosmetic switches.
 *
 * Plus a live preview at the bottom that updates as the user clicks.
 */
public class TierConfigScreen extends Screen {

    private final Screen parent;
    private final List<ModeButton> modeButtons = new ArrayList<>();

    public TierConfigScreen(Screen parent) {
        super(Text.literal("TierTagger – Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        modeButtons.clear();
        TierConfig cfg = TierTaggerCore.config();
        if (cfg == null) {
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"),
                    btn -> closeSafely())
                .dimensions(this.width / 2 - 100, this.height / 2, 200, 20).build());
            return;
        }

        // Layout:
        //   left half  : modes grid
        //   right half : services & badges
        int margin = 14;
        int colGap = 12;
        int leftX  = margin;
        int rightX = this.width / 2 + colGap / 2;
        int leftW  = (this.width / 2) - margin - colGap / 2;
        int rightW = this.width - rightX - margin;
        int top    = 36;

        // -------- Modes section --------
        Set<String> allModes = new LinkedHashSet<>();
        for (TierService s : TierService.values()) {
            if (cfg.isServiceEnabled(s)) allModes.addAll(s.modes);
        }
        if (allModes.isEmpty()) allModes.addAll(TierService.allKnownModes());

        // dedupe synonyms (og_vanilla / ogvanilla, nethpot / nethop)
        LinkedHashSet<String> deduped = new LinkedHashSet<>();
        for (String m : allModes) {
            String c = canonicalMode(m);
            deduped.add(c);
        }

        int modeBtnH = 18;
        int modesPerRow = 2;
        int modeBtnW = (leftW - 6) / modesPerRow;
        int i = 0;
        for (String mode : deduped) {
            int row = i / modesPerRow;
            int col = i % modesPerRow;
            int bx  = leftX + col * (modeBtnW + 6);
            int by  = top + 14 + row * (modeBtnH + 4);
            ModeButton mb = new ModeButton(bx, by, modeBtnW, modeBtnH, mode, cfg);
            this.addDrawableChild(mb);
            modeButtons.add(mb);
            i++;
        }

        // -------- Services section --------
        int sy = top + 14;
        int btnH = 20;

        // Left badge service cycler
        this.addDrawableChild(buildServiceCycler(rightX, sy, rightW, btnH,
                Text.literal("Left badge"), cfg.leftServiceEnum(),
                value -> { cfg.leftService = value.id; cfg.save(); }));
        sy += btnH + 4;

        // Right badge service cycler
        this.addDrawableChild(buildServiceCycler(rightX, sy, rightW, btnH,
                Text.literal("Right badge"), cfg.rightServiceEnum(),
                value -> { cfg.rightService = value.id; cfg.save(); }));
        sy += btnH + 4;

        // Display mode cycler (highest / per gamemode)
        List<String> displayModes = new ArrayList<>();
        displayModes.add("highest");
        for (String m : deduped) displayModes.add(m);
        String currentDisplay = cfg.displayMode == null ? "highest" : cfg.displayMode;
        if (!displayModes.contains(currentDisplay)) currentDisplay = "highest";
        final String initialDisplay = currentDisplay;
        this.addDrawableChild(CyclingButtonWidget.<String>builder(s -> Text.literal(prettyMode(s)))
            .values(displayModes)
            .initially(initialDisplay)
            .build(rightX, sy, rightW, btnH,
                Text.literal("Display"),
                (btn, value) -> { cfg.displayMode = value; cfg.save(); }));
        sy += btnH + 8;

        // Per-service enable toggles
        for (TierService svc : TierService.values()) {
            this.addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.isServiceEnabled(svc))
                .build(rightX, sy, rightW, btnH,
                    Text.literal(svc.displayName),
                    (btn, value) -> {
                        cfg.setServiceEnabled(svc, value);
                        cfg.save();
                        try { TierTaggerCore.cache().invalidate(); } catch (Throwable ignored) {}
                    }));
            sy += btnH + 4;
        }

        // -------- Bottom toggle row --------
        int bottomTop = this.height - 88;
        int tcols = 4;
        int tw = (this.width - margin * 2 - 6 * (tcols - 1)) / tcols;
        int th = 18;
        int tx = margin;

        this.addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.showInTab)
            .build(tx, bottomTop, tw, th, Text.literal("Tab"),
                (b, v) -> { cfg.showInTab = v; cfg.save(); }));
        tx += tw + 6;
        this.addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.showNametag)
            .build(tx, bottomTop, tw, th, Text.literal("Nametag"),
                (b, v) -> { cfg.showNametag = v; cfg.save(); }));
        tx += tw + 6;
        this.addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.coloredBadges)
            .build(tx, bottomTop, tw, th, Text.literal("Colour"),
                (b, v) -> { cfg.coloredBadges = v; cfg.save(); }));
        tx += tw + 6;
        this.addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.rightBadgeEnabled)
            .build(tx, bottomTop, tw, th, Text.literal("Right badge"),
                (b, v) -> { cfg.rightBadgeEnabled = v; cfg.save(); }));

        // Row 2 of bottom toggles
        int row2 = bottomTop + th + 4;
        tx = margin;
        this.addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.showServiceIcon)
            .build(tx, row2, tw, th, Text.literal("Service tag"),
                (b, v) -> { cfg.showServiceIcon = v; cfg.save(); }));
        tx += tw + 6;
        this.addDrawableChild(CyclingButtonWidget.onOffBuilder(!cfg.disableIcons)
            .build(tx, row2, tw, th, Text.literal("Mode icons"),
                (b, v) -> { cfg.disableIcons = !v; cfg.showModeIcon = v; cfg.save(); }));
        tx += tw + 6;
        this.addDrawableChild(CyclingButtonWidget.onOffBuilder(!cfg.disableTiers)
            .build(tx, row2, tw, th, Text.literal("Tier values"),
                (b, v) -> { cfg.disableTiers = !v; cfg.save(); }));
        tx += tw + 6;
        this.addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.showPeak)
            .build(tx, row2, tw, th, Text.literal("Peak tier"),
                (b, v) -> { cfg.showPeak = v; cfg.save(); }));

        // -------- Bottom action row --------
        int actionY = this.height - 28;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Refresh cache"),
                btn -> { try { TierTaggerCore.cache().invalidate(); } catch (Throwable ignored) {} })
            .dimensions(margin, actionY, 120, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"),
                btn -> closeSafely())
            .dimensions(this.width - margin - 100, actionY, 100, 20).build());
    }

    private CyclingButtonWidget<TierService> buildServiceCycler(int x, int y, int w, int h,
                                                                Text label, TierService initial,
                                                                java.util.function.Consumer<TierService> onChange) {
        return CyclingButtonWidget.<TierService>builder(s -> Text.literal(s.displayName).withColor(s.accentArgb))
            .values(TierService.values())
            .initially(initial == null ? TierService.OUTERTIERS : initial)
            .build(x, y, w, h, label,
                (btn, value) -> onChange.accept(value));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        try {
            this.renderBackground(ctx, mouseX, mouseY, delta);
            super.render(ctx, mouseX, mouseY, delta);

            ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 14, 0xFFFFFFFF);

            // Section headers
            int margin = 14;
            ctx.drawTextWithShadow(this.textRenderer,
                Text.literal("Modes").formatted(Formatting.GOLD), margin, 36, 0xFFAA00);
            ctx.drawTextWithShadow(this.textRenderer,
                Text.literal("Services & badges").formatted(Formatting.GOLD),
                this.width / 2 + 6, 36, 0xFFAA00);

            // Live preview strip just above the action buttons
            renderPreview(ctx);
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] config screen render failed: {}", t.toString());
        }
    }

    private void renderPreview(DrawContext ctx) {
        TierConfig cfg = TierTaggerCore.config();
        if (cfg == null) return;
        int previewY = this.height - 50;
        int margin = 14;
        ctx.fill(margin, previewY, this.width - margin, previewY + 16, 0xCC101418);
        ctx.drawBorder(margin, previewY, this.width - margin * 2, 16, 0xFF000000);

        // Mock badges around a player name "Steve" (good enough for a live demo)
        String left  = "HT3";
        String right = "HT2";
        TierService leftSvc  = cfg.leftServiceEnum();
        TierService rightSvc = cfg.rightServiceEnum();

        MutableText line = Text.empty();
        if (cfg.showInTab || cfg.showNametag) {
            String leftText = (cfg.showServiceIcon ? leftSvc.shortLabel + " " : "") + "[" + left + "]";
            line = line.append(Text.literal(leftText).withColor(TierTaggerCore.argbFor(left))).append(Text.literal(" "));
        }
        line = line.append(Text.literal("Steve").formatted(Formatting.WHITE));
        if ((cfg.showInTab || cfg.showNametag) && cfg.rightBadgeEnabled) {
            String rightText = "[" + right + "]" + (cfg.showServiceIcon ? " " + rightSvc.shortLabel : "");
            line = line.append(Text.literal(" "))
                       .append(Text.literal(rightText).withColor(TierTaggerCore.argbFor(right)));
        }
        int textW = this.textRenderer.getWidth(line);
        ctx.drawTextWithShadow(this.textRenderer, line, this.width / 2 - textW / 2, previewY + 4, 0xFFFFFFFF);
    }

    private void closeSafely() {
        MinecraftClient mc = (this.client != null) ? this.client : MinecraftClient.getInstance();
        if (mc != null) mc.setScreen(parent);
    }

    @Override
    public void close() { closeSafely(); }

    private static String canonicalMode(String mode) {
        if (mode == null) return "";
        String m = mode.toLowerCase(Locale.ROOT);
        if (m.equals("og_vanilla")) return "ogvanilla";
        if (m.equals("nethpot"))    return "nethop";
        return m;
    }

    private static String prettyMode(String s) {
        if (s == null) return "";
        if (s.equalsIgnoreCase("highest")) return "Highest";
        return TierIcons.labelFor(s);
    }

    // ---------------- mode checkbox button ----------------

    private static class ModeButton extends ButtonWidget {
        private final String mode;
        private final TierConfig cfg;

        ModeButton(int x, int y, int w, int h, String mode, TierConfig cfg) {
            super(x, y, w, h, Text.literal(TierIcons.labelFor(mode)),
                btn -> { /* set on construction */ }, ButtonWidget.DEFAULT_NARRATION_SUPPLIER);
            this.mode = mode;
            this.cfg  = cfg;
        }

        @Override
        public void onPress() {
            cfg.setModeEnabled(mode, !cfg.isModeEnabled(mode));
            cfg.save();
        }

        @Override
        protected void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
            // Background
            boolean enabled = cfg.isModeEnabled(mode);
            int bg = isHovered() ? 0xFF2D343C : 0xFF1A1F25;
            int border = enabled ? 0xFF55FF55 : 0xFF555555;
            ctx.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), bg);
            ctx.drawBorder(getX(), getY(), getWidth(), getHeight(), border);

            // Checkbox indicator
            int cbX = getX() + 4;
            int cbY = getY() + (getHeight() - 8) / 2;
            ctx.fill(cbX, cbY, cbX + 8, cbY + 8, enabled ? 0xFF55FF55 : 0xFF333333);
            ctx.drawBorder(cbX, cbY, 8, 8, 0xFF000000);

            // Item icon
            int iconX = cbX + 12;
            int iconY = getY() + (getHeight() - 10) / 2;
            try {
                Identifier id = Identifier.tryParse(TierIcons.iconFor(mode));
                if (id != null) {
                    Item item = com.outertiers.tiertagger.fabric.compat.Compat.lookupItem(id);
                    ItemStack stack = item == null ? ItemStack.EMPTY : new ItemStack(item);
                    if (!stack.isEmpty()) {
                        ctx.getMatrices().push();
                        ctx.getMatrices().translate(iconX, iconY - 1, 0);
                        ctx.getMatrices().scale(0.625f, 0.625f, 1f);
                        ctx.drawItem(stack, 0, 0);
                        ctx.getMatrices().pop();
                    }
                }
            } catch (Throwable ignored) {}

            // Label
            int textX = iconX + 12;
            int textY = getY() + (getHeight() - 8) / 2;
            ctx.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,
                Text.literal(TierIcons.labelFor(mode))
                    .formatted(enabled ? Formatting.WHITE : Formatting.GRAY),
                textX, textY, enabled ? 0xFFFFFF : 0xAAAAAA);
        }
    }
}

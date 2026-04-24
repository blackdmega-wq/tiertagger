package com.outertiers.tiertagger.neoforge.screen;

import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierIcons;
import com.outertiers.tiertagger.common.TierService;
import com.outertiers.tiertagger.common.TierTaggerCore;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** MCTiers-style config screen with mode grid, service selectors and live preview. */
public class TierConfigScreen extends Screen {

    private final Screen parent;
    private final List<ModeButton> modeButtons = new ArrayList<>();

    public TierConfigScreen(Screen parent) {
        super(Component.literal("TierTagger – Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        modeButtons.clear();
        TierConfig cfg = TierTaggerCore.config();
        if (cfg == null) {
            this.addRenderableWidget(Button.builder(Component.literal("Close"),
                    btn -> closeSafely())
                .bounds(this.width / 2 - 100, this.height / 2, 200, 20).build());
            return;
        }

        int margin = 14;
        int colGap = 12;
        int leftX  = margin;
        int rightX = this.width / 2 + colGap / 2;
        int leftW  = (this.width / 2) - margin - colGap / 2;
        int rightW = this.width - rightX - margin;
        int top    = 36;

        // Modes section
        Set<String> allModes = new LinkedHashSet<>();
        for (TierService s : TierService.values()) {
            if (cfg.isServiceEnabled(s)) allModes.addAll(s.modes);
        }
        if (allModes.isEmpty()) allModes.addAll(TierService.allKnownModes());
        LinkedHashSet<String> deduped = new LinkedHashSet<>();
        for (String m : allModes) deduped.add(canonicalMode(m));

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
            this.addRenderableWidget(mb);
            modeButtons.add(mb);
            i++;
        }

        // Services section
        int sy = top + 14;
        int btnH = 20;

        this.addRenderableWidget(buildServiceCycler(rightX, sy, rightW, btnH,
                Component.literal("Left badge"), cfg.leftServiceEnum(),
                value -> { cfg.leftService = value.id; cfg.save(); }));
        sy += btnH + 4;
        this.addRenderableWidget(buildServiceCycler(rightX, sy, rightW, btnH,
                Component.literal("Right badge"), cfg.rightServiceEnum(),
                value -> { cfg.rightService = value.id; cfg.save(); }));
        sy += btnH + 4;

        List<String> displayModes = new ArrayList<>();
        displayModes.add("highest");
        for (String m : deduped) displayModes.add(m);
        String currentDisplay = cfg.displayMode == null ? "highest" : cfg.displayMode;
        if (!displayModes.contains(currentDisplay)) currentDisplay = "highest";
        final String initialDisplay = currentDisplay;
        this.addRenderableWidget(CycleButton.<String>builder(s -> Component.literal(prettyMode(s)))
            .withValues(displayModes)
            .withInitialValue(initialDisplay)
            .create(rightX, sy, rightW, btnH,
                Component.literal("Display"),
                (btn, value) -> { cfg.displayMode = value; cfg.save(); }));
        sy += btnH + 8;

        for (TierService svc : TierService.values()) {
            this.addRenderableWidget(CycleButton.onOffBuilder(cfg.isServiceEnabled(svc))
                .create(rightX, sy, rightW, btnH,
                    Component.literal(svc.displayName),
                    (btn, value) -> {
                        cfg.setServiceEnabled(svc, value);
                        cfg.save();
                        try { TierTaggerCore.cache().invalidate(); } catch (Throwable ignored) {}
                    }));
            sy += btnH + 4;
        }

        // Bottom toggles row
        int bottomTop = this.height - 88;
        int tcols = 4;
        int tw = (this.width - margin * 2 - 6 * (tcols - 1)) / tcols;
        int th = 18;
        int tx = margin;

        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.showInTab)
            .create(tx, bottomTop, tw, th, Component.literal("Tab"),
                (b, v) -> { cfg.showInTab = v; cfg.save(); }));
        tx += tw + 6;
        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.showNametag)
            .create(tx, bottomTop, tw, th, Component.literal("Nametag"),
                (b, v) -> { cfg.showNametag = v; cfg.save(); }));
        tx += tw + 6;
        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.coloredBadges)
            .create(tx, bottomTop, tw, th, Component.literal("Colour"),
                (b, v) -> { cfg.coloredBadges = v; cfg.save(); }));
        tx += tw + 6;
        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.rightBadgeEnabled)
            .create(tx, bottomTop, tw, th, Component.literal("Right badge"),
                (b, v) -> { cfg.rightBadgeEnabled = v; cfg.save(); }));

        int row2 = bottomTop + th + 4;
        tx = margin;
        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.showServiceIcon)
            .create(tx, row2, tw, th, Component.literal("Service tag"),
                (b, v) -> { cfg.showServiceIcon = v; cfg.save(); }));
        tx += tw + 6;
        this.addRenderableWidget(CycleButton.onOffBuilder(!cfg.disableIcons)
            .create(tx, row2, tw, th, Component.literal("Mode icons"),
                (b, v) -> { cfg.disableIcons = !v; cfg.showModeIcon = v; cfg.save(); }));
        tx += tw + 6;
        this.addRenderableWidget(CycleButton.onOffBuilder(!cfg.disableTiers)
            .create(tx, row2, tw, th, Component.literal("Tier values"),
                (b, v) -> { cfg.disableTiers = !v; cfg.save(); }));
        tx += tw + 6;
        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.showPeak)
            .create(tx, row2, tw, th, Component.literal("Peak tier"),
                (b, v) -> { cfg.showPeak = v; cfg.save(); }));

        int actionY = this.height - 28;
        this.addRenderableWidget(Button.builder(Component.literal("Refresh cache"),
                btn -> { try { TierTaggerCore.cache().invalidate(); } catch (Throwable ignored) {} })
            .bounds(margin, actionY, 120, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Done"),
                btn -> closeSafely())
            .bounds(this.width - margin - 100, actionY, 100, 20).build());
    }

    private CycleButton<TierService> buildServiceCycler(int x, int y, int w, int h,
                                                       Component label, TierService initial,
                                                       java.util.function.Consumer<TierService> onChange) {
        return CycleButton.<TierService>builder(s -> Component.literal(s.displayName).withColor(s.accentArgb))
            .withValues(TierService.values())
            .withInitialValue(initial == null ? TierService.OUTERTIERS : initial)
            .create(x, y, w, h, label, (btn, value) -> onChange.accept(value));
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        try {
            super.render(ctx, mouseX, mouseY, delta);
            ctx.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFF);

            int margin = 14;
            ctx.drawString(this.font,
                Component.literal("Modes").withStyle(ChatFormatting.GOLD), margin, 36, 0xFFAA00);
            ctx.drawString(this.font,
                Component.literal("Services & badges").withStyle(ChatFormatting.GOLD),
                this.width / 2 + 6, 36, 0xFFAA00);

            renderPreview(ctx);
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] config screen render failed: {}", t.toString());
        }
    }

    private void renderPreview(GuiGraphics ctx) {
        TierConfig cfg = TierTaggerCore.config();
        if (cfg == null) return;
        int previewY = this.height - 50;
        int margin = 14;
        ctx.fill(margin, previewY, this.width - margin, previewY + 16, 0xCC101418);
        ctx.renderOutline(margin, previewY, this.width - margin * 2, 16, 0xFF000000);

        String left  = "HT3";
        String right = "HT2";
        TierService leftSvc  = cfg.leftServiceEnum();
        TierService rightSvc = cfg.rightServiceEnum();

        MutableComponent line = Component.empty();
        if (cfg.showInTab || cfg.showNametag) {
            String leftText = (cfg.showServiceIcon ? leftSvc.shortLabel + " " : "") + "[" + left + "]";
            line = line.append(Component.literal(leftText).withColor(TierTaggerCore.argbFor(left))).append(Component.literal(" "));
        }
        line = line.append(Component.literal("Steve").withStyle(ChatFormatting.WHITE));
        if ((cfg.showInTab || cfg.showNametag) && cfg.rightBadgeEnabled) {
            String rightText = "[" + right + "]" + (cfg.showServiceIcon ? " " + rightSvc.shortLabel : "");
            line = line.append(Component.literal(" "))
                       .append(Component.literal(rightText).withColor(TierTaggerCore.argbFor(right)));
        }
        int textW = this.font.width(line);
        ctx.drawString(this.font, line, this.width / 2 - textW / 2, previewY + 4, 0xFFFFFFFF);
    }

    private void closeSafely() {
        Minecraft mc = (this.minecraft != null) ? this.minecraft : Minecraft.getInstance();
        if (mc != null) mc.setScreen(parent);
    }

    @Override
    public void onClose() { closeSafely(); }

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

    private static class ModeButton extends AbstractButton {
        private final String mode;
        private final TierConfig cfg;

        ModeButton(int x, int y, int w, int h, String mode, TierConfig cfg) {
            super(x, y, w, h, Component.literal(TierIcons.labelFor(mode)));
            this.mode = mode;
            this.cfg  = cfg;
        }

        @Override
        public void onPress() {
            cfg.setModeEnabled(mode, !cfg.isModeEnabled(mode));
            cfg.save();
        }

        @Override
        protected void renderWidget(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
            boolean enabled = cfg.isModeEnabled(mode);
            int bg = isHovered() ? 0xFF2D343C : 0xFF1A1F25;
            int border = enabled ? 0xFF55FF55 : 0xFF555555;
            ctx.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), bg);
            ctx.renderOutline(getX(), getY(), getWidth(), getHeight(), border);

            int cbX = getX() + 4;
            int cbY = getY() + (getHeight() - 8) / 2;
            ctx.fill(cbX, cbY, cbX + 8, cbY + 8, enabled ? 0xFF55FF55 : 0xFF333333);
            ctx.renderOutline(cbX, cbY, 8, 8, 0xFF000000);

            int iconX = cbX + 12;
            int iconY = getY() + (getHeight() - 10) / 2;
            try {
                ResourceLocation id = ResourceLocation.tryParse(TierIcons.iconFor(mode));
                if (id != null) {
                    ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(id));
                    if (!stack.isEmpty()) {
                        ctx.pose().pushPose();
                        ctx.pose().translate(iconX, iconY - 1, 0);
                        ctx.pose().scale(0.625f, 0.625f, 1f);
                        ctx.renderItem(stack, 0, 0);
                        ctx.pose().popPose();
                    }
                }
            } catch (Throwable ignored) {}

            int textX = iconX + 12;
            int textY = getY() + (getHeight() - 8) / 2;
            ctx.drawString(Minecraft.getInstance().font,
                Component.literal(TierIcons.labelFor(mode))
                    .withStyle(enabled ? ChatFormatting.WHITE : ChatFormatting.GRAY),
                textX, textY, enabled ? 0xFFFFFF : 0xAAAAAA);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            this.defaultButtonNarrationText(out);
        }
    }
}

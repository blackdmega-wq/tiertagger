package com.outertiers.tiertagger.neoforge.screen;

import com.outertiers.tiertagger.common.PlayerData;
import com.outertiers.tiertagger.common.Ranking;
import com.outertiers.tiertagger.common.ServiceData;
import com.outertiers.tiertagger.common.TierService;
import com.outertiers.tiertagger.common.TierTaggerCore;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Optional;

/** GUI for /tiertagger lookup — NeoForge variant. */
public class TierLookupScreen extends Screen {

    private final Screen parent;
    private final String username;
    private boolean bgApplied = false;

    private int scrollY   = 0;
    private int maxScroll = 0;

    private static final int PAD = 12;

    public TierLookupScreen(Screen parent, String username) {
        super(Component.literal("TierTagger – Lookup: " + (username == null ? "?" : username)));
        this.parent   = parent;
        this.username = username == null ? "" : username;
    }

    @Override
    protected void init() {
        try { TierTaggerCore.cache().peekData(username); } catch (Throwable ignored) {}
        scrollY = 0;

        int btnY = this.height - 25;
        this.addRenderableWidget(Button.builder(Component.literal("Update"), btn -> {
            TierTaggerCore.cache().invalidatePlayer(username);
            try { TierTaggerCore.cache().peekData(username); } catch (Throwable ignored) {}
        }).bounds(PAD, btnY, 80, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Close"), btn -> closeSafely())
            .bounds(this.width - PAD - 80, btnY, 80, 20).build());
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hd, double vd) {
        scrollY = Math.max(0, Math.min(maxScroll, scrollY - (int)(vd * 12)));
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        if (bgApplied) return;
        bgApplied = true;
        super.renderBackground(ctx, mouseX, mouseY, delta);
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        bgApplied = false;
        try {
            this.renderBackground(ctx, mouseX, mouseY, delta);

            ctx.drawCenteredString(this.font,
                Component.literal("Lookup: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(username).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)),
                this.width / 2, 8, 0xFFFFFF);

            int contentTop    = 24;
            int contentBottom = this.height - 30;
            ctx.enableScissor(0, contentTop, this.width, contentBottom);
            int y = drawContent(ctx, contentTop - scrollY);
            ctx.disableScissor();
            maxScroll = Math.max(0, y + scrollY - contentBottom);

            super.render(ctx, mouseX, mouseY, delta);
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] lookup render: {}", t.toString());
        }
    }

    private int drawContent(GuiGraphics ctx, int startY) {
        int x = PAD;
        int y = startY;
        int w = this.width - PAD * 2;

        Optional<PlayerData> opt = TierTaggerCore.cache().peekData(username);
        PlayerData data = opt.orElse(null);

        if (data == null) {
            ctx.drawString(this.font,
                Component.literal("Loading…").withStyle(ChatFormatting.DARK_GRAY), x, y + 4, 0x888888, true);
            return y + 20;
        }

        for (TierService svc : TierService.values()) {
            ServiceData sd = data.get(svc);

            ctx.fill(x, y, x + w, y + 1, svc.accentArgb);
            y += 3;

            MutableComponent header = Component.literal(svc.displayName)
                .withColor(svc.accentArgb).withStyle(ChatFormatting.BOLD);

            if (sd != null && !sd.missing && sd.fetchedAt > 0) {
                if (sd.region != null && !sd.region.isBlank())
                    header = header.append(Component.literal("  (" + sd.region + ")").withStyle(ChatFormatting.AQUA));
                if (sd.overall > 0)
                    header = header.append(Component.literal("  #" + sd.overall).withStyle(ChatFormatting.GOLD));
            }
            ctx.drawString(this.font, header, x, y, 0xFFFFFF, true);
            y += 13;

            if (sd == null || sd.fetchedAt == 0L) {
                ctx.drawString(this.font, Component.literal("  Loading…").withStyle(ChatFormatting.DARK_GRAY), x, y, 0x888888, true);
                y += 12;
            } else if (sd.missing) {
                ctx.drawString(this.font,
                    Component.literal("  Not ranked on " + svc.displayName).withStyle(ChatFormatting.DARK_GRAY),
                    x, y, 0x666666, true);
                y += 12;
            } else {
                boolean any = false;
                for (String mode : svc.modes) {
                    Ranking r = sd.rankings.get(mode);
                    if (r == null || r.tierLevel <= 0) continue;

                    String modeLabel = mode.replace("_", " ");
                    String curLabel  = r.label();
                    int    curColor  = TierTaggerCore.argbFor(curLabel);

                    ctx.drawString(this.font,
                        Component.literal("  " + modeLabel).withStyle(ChatFormatting.GRAY),
                        x, y, 0xCCCCCC, true);

                    MutableComponent tierTxt = Component.literal(curLabel).withColor(curColor).withStyle(ChatFormatting.BOLD);
                    if (r.peakDiffers()) {
                        String pk = r.peakLabel();
                        tierTxt = tierTxt.append(Component.literal(" (" + pk + ")").withColor(TierTaggerCore.argbFor(pk)));
                    }
                    int tw = this.font.width(tierTxt);
                    ctx.drawString(this.font, tierTxt, x + w - 10 - tw, y, curColor, true);
                    y += 12;
                    any = true;
                }
                if (!any) {
                    ctx.drawString(this.font,
                        Component.literal("  (no modes)").withStyle(ChatFormatting.DARK_GRAY),
                        x, y, 0x666666, true);
                    y += 12;
                }
            }
            y += 4;
        }
        return y;
    }

    private void closeSafely() {
        Minecraft mc = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        if (mc != null) mc.setScreen(parent);
    }

    @Override
    public void onClose() { closeSafely(); }
}

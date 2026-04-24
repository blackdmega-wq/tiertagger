package com.outertiers.tiertagger.neoforge.screen;

import com.outertiers.tiertagger.common.PlayerData;
import com.outertiers.tiertagger.common.Ranking;
import com.outertiers.tiertagger.common.ServiceData;
import com.outertiers.tiertagger.common.TierIcons;
import com.outertiers.tiertagger.common.TierService;
import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.neoforge.compat.Compat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/** Tiers-Mod-style lookup GUI (NeoForge). Mirrors the Fabric design exactly. */
public class TierLookupScreen extends Screen {

    private static final ResourceLocation STEVE =
        ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png");

    private static final int PANEL_W_MAX = 480;
    private static final int CARD_GAP    = 6;
    private static final int CARD_PAD    = 8;
    private static final int ROW_H       = 14;
    private static final int ICON_SIZE   = 16;

    private static final int BG_PANEL    = 0xF20E1116;
    private static final int BG_PANEL_BORDER = 0xFF2A2F38;
    private static final int BG_HEADER   = 0xFF181C24;
    private static final int BG_CARD     = 0xFF15191F;
    private static final int BG_CARD_BAR = 0xFF1E232C;
    private static final int FG_FAINT    = 0x9AA0AA;
    private static final int FG_TEXT     = 0xE6E8EC;

    private final Screen parent;
    private final String username;
    private boolean bgApplied = false;
    private int scrollY = 0;
    private int maxScroll = 0;

    public TierLookupScreen(Screen parent, String username) {
        super(Component.literal("TierTagger – Lookup: " + (username == null ? "?" : username)));
        this.parent   = parent;
        this.username = username == null ? "" : username;
    }

    @Override
    protected void init() {
        try { TierTaggerCore.cache().peekData(username); } catch (Throwable ignored) {}
        scrollY = 0;

        int panelW = Math.min(PANEL_W_MAX, this.width - 40);
        int panelX = (this.width - panelW) / 2;
        int btnY   = this.height - 28;

        this.addRenderableWidget(Button.builder(Component.literal("\u21BB Update"), btn -> {
                try {
                    TierTaggerCore.cache().invalidatePlayer(username);
                    TierTaggerCore.cache().peekData(username);
                } catch (Throwable ignored) {}
            })
            .bounds(panelX + CARD_PAD, btnY, 80, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Close"), btn -> closeSafely())
            .bounds(panelX + panelW - CARD_PAD - 80, btnY, 80, 20).build());
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

            fillRect(ctx, panelX, panelTop, panelX + panelW, panelBottom, BG_PANEL);
            outlineRect(ctx, panelX, panelTop, panelW, panelBottom - panelTop, BG_PANEL_BORDER);

            int headerH = 50;
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
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] lookup render: {}", t.toString());
        }
    }

    private void renderHeader(GuiGraphics ctx, int x, int y, int w, int h) {
        fillRect(ctx, x, y, x + w, y + h, BG_HEADER);

        int headSize = h - 12;
        int headX = x + 8;
        int headY = y + (h - headSize) / 2;
        drawHead(ctx, username, headX, headY, headSize);

        int textX = headX + headSize + 10;
        int textY = y + 8;
        ctx.drawString(this.font,
            Component.literal(username).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD),
            textX, textY, 0xFFFFFFFF, true);

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
            subColor = TierTaggerCore.argbFor(best.label()) & 0xFFFFFF;
        } else if (opt.isEmpty()) {
            sub = "Loading…"; subColor = FG_FAINT;
        } else {
            sub = "No tiers found"; subColor = FG_FAINT;
        }
        ctx.drawString(this.font,
            Component.literal(sub).withColor(subColor & 0xFFFFFF),
            textX, textY + 12, subColor & 0xFFFFFF, true);

        if (opt.isPresent()) {
            int ranked = 0, loaded = 0;
            for (TierService s : TierService.values()) {
                ServiceData sd = opt.get().get(s);
                if (sd.fetchedAt > 0) loaded++;
                if (sd.rankedCount() > 0) ranked++;
            }
            String st = ranked + " / " + TierService.values().length + " ranked";
            if (loaded < TierService.values().length) st = "loading " + loaded + "/" + TierService.values().length;
            int sw = this.font.width(st);
            ctx.drawString(this.font,
                Component.literal(st).withColor(FG_FAINT),
                x + w - 10 - sw, textY + 6, FG_FAINT, true);
        }
    }

    private int renderCards(GuiGraphics ctx, int x, int y, int w) {
        Optional<PlayerData> opt = TierTaggerCore.cache().peekData(username);
        if (opt.isEmpty()) {
            ctx.drawCenteredString(this.font,
                Component.literal("Looking up player…").withStyle(ChatFormatting.GRAY),
                x + w / 2, y + 20, 0xAAAAAA);
            return y + 40;
        }
        PlayerData data = opt.get();

        for (TierService svc : TierService.values()) {
            ServiceData sd = data.get(svc);
            int rows = Math.max(1, sd.rankings.size());
            int cardH = 22 + rows * ROW_H + 6;
            renderServiceCard(ctx, svc, sd, x, y, w, cardH);
            y += cardH + CARD_GAP;
        }
        return y;
    }

    private void renderServiceCard(GuiGraphics ctx, TierService svc, ServiceData sd,
                                   int x, int y, int w, int h) {
        int accent = svc.accentArgb;

        fillRect(ctx, x, y, x + w, y + h, BG_CARD);
        outlineRect(ctx, x, y, w, h, 0xFF2A2F38);
        fillRect(ctx, x, y, x + 3, y + h, accent);
        fillRect(ctx, x + 3, y, x + w, y + 22, BG_CARD_BAR);

        ctx.drawString(this.font,
            Component.literal(svc.shortLabel).withColor(accent & 0xFFFFFF).copy().withStyle(ChatFormatting.BOLD),
            x + 10, y + 7, accent & 0xFFFFFF, true);
        ctx.drawString(this.font,
            Component.literal(svc.displayName).withStyle(ChatFormatting.WHITE),
            x + 10 + this.font.width(svc.shortLabel) + 6, y + 7, FG_TEXT, true);

        String rightStr;
        int rightCol;
        if (sd.fetchedAt == 0L)      { rightStr = "loading…";       rightCol = FG_FAINT; }
        else if (sd.missing)         { rightStr = "not ranked";     rightCol = FG_FAINT; }
        else {
            String reg = (sd.region == null || sd.region.isBlank()) ? "??" : sd.region;
            rightStr = reg + (sd.overall > 0 ? "  ·  #" + sd.overall : "");
            rightCol = 0xFFD27A;
        }
        int rw = this.font.width(rightStr);
        ctx.drawString(this.font, Component.literal(rightStr).withColor(rightCol & 0xFFFFFF),
            x + w - 10 - rw, y + 7, rightCol & 0xFFFFFF, true);

        int rowY = y + 24;
        int innerX = x + 10;
        int innerW = w - 20;

        if (sd.fetchedAt == 0L) {
            ctx.drawString(this.font,
                Component.literal("Fetching " + svc.displayName + "…").withStyle(ChatFormatting.DARK_GRAY),
                innerX, rowY + 2, 0x808080, true);
            return;
        }
        if (sd.missing || sd.rankings.isEmpty()) {
            ctx.drawString(this.font,
                Component.literal("Player has no entry on this tier-list").withStyle(ChatFormatting.DARK_GRAY),
                innerX, rowY + 2, 0x808080, true);
            return;
        }

        boolean alt = false;
        for (String mode : svc.modes) {
            Ranking r = sd.rankings.get(mode);
            if (r == null || r.tierLevel <= 0) continue;
            renderModeRow(ctx, mode, r, innerX, rowY, innerW, alt);
            rowY += ROW_H;
            alt = !alt;
        }
    }

    private void renderModeRow(GuiGraphics ctx, String mode, Ranking r,
                               int x, int y, int w, boolean alt) {
        if (alt) fillRect(ctx, x - 4, y, x + w + 4, y + ROW_H, 0x14FFFFFF);

        int textX = x;
        try {
            ResourceLocation id = ResourceLocation.tryParse(TierIcons.iconFor(mode));
            if (id != null) {
                Item item = Compat.lookupItem(id);
                ItemStack stack = item == null ? ItemStack.EMPTY : new ItemStack(item);
                if (!stack.isEmpty()) {
                    ctx.renderItem(stack, x, y - 1);
                    textX = x + ICON_SIZE + 4;
                }
            }
        } catch (Throwable ignored) {}

        String label = TierIcons.labelFor(mode);
        ctx.drawString(this.font,
            Component.literal(label).withColor(FG_TEXT),
            textX, y + 3, FG_TEXT, true);

        String cur = r.label();
        int curColor = TierTaggerCore.argbFor(cur) & 0xFFFFFF;
        MutableComponent tier = Component.literal(cur).withColor(curColor).copy().withStyle(ChatFormatting.BOLD);
        if (r.peakDiffers()) {
            String peak = r.peakLabel();
            int peakC = TierTaggerCore.argbFor(peak) & 0xFFFFFF;
            tier = tier.append(Component.literal(" · peak ").withStyle(ChatFormatting.DARK_GRAY))
                       .append(Component.literal(peak).withColor(peakC));
        }
        if (r.retired) {
            tier = Component.literal("(").withStyle(ChatFormatting.DARK_GRAY).append(tier)
                       .append(Component.literal(")").withStyle(ChatFormatting.DARK_GRAY));
        }
        int tw = this.font.width(tier);
        ctx.drawString(this.font, tier, x + w - tw, y + 3, 0xFFFFFFFF, true);
    }

    private void drawHead(GuiGraphics ctx, String name, int x, int y, int size) {
        PlayerSkin skin = null;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getConnection() != null) {
                PlayerInfo p = mc.getConnection().getPlayerInfo(name);
                if (p != null) skin = p.getSkin();
            }
        } catch (Throwable ignored) {}
        try {
            ctx.fill(x - 2, y - 2, x + size + 2, y + size + 2, 0xFF1A1A1A);
            Compat.drawPlayerFace(ctx, skin, STEVE, x, y, size);
        } catch (Throwable t) {
            ctx.fill(x, y, x + size, y + size, 0xFF6E4A2A);
        }
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
        Minecraft mc = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        if (mc != null) mc.setScreen(parent);
    }

    @Override
    public void onClose() { closeSafely(); }
}

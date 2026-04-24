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

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/** Side-by-side compare GUI (NeoForge). Mirrors Fabric design exactly. */
public class TierCompareScreen extends Screen {

    private static final ResourceLocation STEVE =
        ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png");

    private static final int PANEL_W_MAX = 560;
    private static final int CARD_GAP    = 6;
    private static final int CARD_PAD    = 8;
    private static final int ROW_H       = 14;

    private static final int BG_PANEL    = 0xF20E1116;
    private static final int BG_PANEL_BORDER = 0xFF2A2F38;
    private static final int BG_HEADER   = 0xFF181C24;
    private static final int BG_CARD     = 0xFF15191F;
    private static final int BG_CARD_BAR = 0xFF1E232C;
    private static final int FG_FAINT    = 0x9AA0AA;
    private static final int FG_TEXT     = 0xE6E8EC;

    private final Screen parent;
    private final String nameA;
    private final String nameB;
    private boolean bgApplied = false;
    private int scrollY = 0;
    private int maxScroll = 0;

    public TierCompareScreen(Screen parent, String nameA, String nameB) {
        super(Component.literal(nameA + " vs " + nameB));
        this.parent = parent;
        this.nameA  = nameA == null ? "" : nameA;
        this.nameB  = nameB == null ? "" : nameB;
    }

    @Override
    protected void init() {
        try { TierTaggerCore.cache().peekData(nameA); } catch (Throwable ignored) {}
        try { TierTaggerCore.cache().peekData(nameB); } catch (Throwable ignored) {}
        scrollY = 0;

        int panelW = Math.min(PANEL_W_MAX, this.width - 40);
        int panelX = (this.width - panelW) / 2;
        int btnY   = this.height - 28;

        this.addRenderableWidget(Button.builder(Component.literal("\u21BB " + nameA), btn -> {
                try { TierTaggerCore.cache().invalidatePlayer(nameA);
                      TierTaggerCore.cache().peekData(nameA); } catch (Throwable ignored) {}
            })
            .bounds(panelX + CARD_PAD, btnY, 90, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Close"), btn -> closeSafely())
            .bounds(this.width / 2 - 40, btnY, 80, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("\u21BB " + nameB), btn -> {
                try { TierTaggerCore.cache().invalidatePlayer(nameB);
                      TierTaggerCore.cache().peekData(nameB); } catch (Throwable ignored) {}
            })
            .bounds(panelX + panelW - CARD_PAD - 90, btnY, 90, 20).build());
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

            Optional<PlayerData> optA = TierTaggerCore.cache().peekData(nameA);
            Optional<PlayerData> optB = TierTaggerCore.cache().peekData(nameB);
            PlayerData dA = optA.orElse(null);
            PlayerData dB = optB.orElse(null);

            int headerH = 50;
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
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] compare render: {}", t.toString());
        }
    }

    private void renderHeader(GuiGraphics ctx, PlayerData dA, PlayerData dB,
                              int x, int y, int w, int h) {
        fillRect(ctx, x, y, x + w, y + h, BG_HEADER);

        int headSize = h - 12;
        int leftHeadX  = x + 8;
        int rightHeadX = x + w - 8 - headSize;
        int headY      = y + (h - headSize) / 2;
        drawHead(ctx, nameA, leftHeadX,  headY, headSize);
        drawHead(ctx, nameB, rightHeadX, headY, headSize);

        ctx.drawString(this.font,
            Component.literal(nameA).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD),
            leftHeadX + headSize + 8, y + 8, 0xFFFFFFFF, true);
        ctx.drawString(this.font,
            Component.literal(nameB).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD),
            rightHeadX - 8 - this.font.width(nameB), y + 8, 0xFFFFFFFF, true);

        Ranking bestA = highestOverall(dA);
        Ranking bestB = highestOverall(dB);
        if (bestA != null) {
            String s = bestA.label();
            int c = TierTaggerCore.argbFor(s) & 0xFFFFFF;
            ctx.drawString(this.font, Component.literal(s).withColor(c).copy().withStyle(ChatFormatting.BOLD),
                leftHeadX + headSize + 8, y + 22, c, true);
        } else if (dA == null) {
            ctx.drawString(this.font, Component.literal("loading…").withColor(FG_FAINT),
                leftHeadX + headSize + 8, y + 22, FG_FAINT, true);
        }
        if (bestB != null) {
            String s = bestB.label();
            int c = TierTaggerCore.argbFor(s) & 0xFFFFFF;
            int sw = this.font.width(s);
            ctx.drawString(this.font, Component.literal(s).withColor(c).copy().withStyle(ChatFormatting.BOLD),
                rightHeadX - 8 - sw, y + 22, c, true);
        } else if (dB == null) {
            int sw = this.font.width("loading…");
            ctx.drawString(this.font, Component.literal("loading…").withColor(FG_FAINT),
                rightHeadX - 8 - sw, y + 22, FG_FAINT, true);
        }

        ctx.drawCenteredString(this.font,
            Component.literal("vs").withStyle(ChatFormatting.GRAY, ChatFormatting.BOLD),
            x + w / 2, y + h / 2 - 4, 0xCCCCCC);
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

    private int renderCards(GuiGraphics ctx, PlayerData dA, PlayerData dB, int x, int y, int w) {
        int wins1 = 0, wins2 = 0, ties = 0;

        for (TierService svc : TierService.values()) {
            ServiceData sdA = dA == null ? null : dA.get(svc);
            ServiceData sdB = dB == null ? null : dB.get(svc);

            Set<String> allModes = new LinkedHashSet<>(svc.modes);
            if (sdA != null) allModes.addAll(sdA.rankings.keySet());
            if (sdB != null) allModes.addAll(sdB.rankings.keySet());

            int rowsToDraw = 0;
            for (String m : allModes) {
                Ranking rA = sdA == null ? null : sdA.rankings.get(m);
                Ranking rB = sdB == null ? null : sdB.rankings.get(m);
                if ((rA != null && rA.tierLevel > 0) || (rB != null && rB.tierLevel > 0)) rowsToDraw++;
            }
            int cardH = 22 + Math.max(1, rowsToDraw) * ROW_H + 6;

            fillRect(ctx, x, y, x + w, y + cardH, BG_CARD);
            outlineRect(ctx, x, y, w, cardH, 0xFF2A2F38);
            fillRect(ctx, x, y, x + 3, y + cardH, svc.accentArgb);
            fillRect(ctx, x + 3, y, x + w, y + 22, BG_CARD_BAR);

            ctx.drawString(this.font,
                Component.literal(svc.shortLabel).withColor(svc.accentArgb & 0xFFFFFF).copy().withStyle(ChatFormatting.BOLD),
                x + 10, y + 7, svc.accentArgb & 0xFFFFFF, true);
            ctx.drawString(this.font,
                Component.literal(svc.displayName).withStyle(ChatFormatting.WHITE),
                x + 10 + this.font.width(svc.shortLabel) + 6, y + 7, FG_TEXT, true);

            String regs = regionPair(sdA, sdB);
            int rsw = this.font.width(regs);
            ctx.drawString(this.font, Component.literal(regs).withColor(FG_FAINT),
                x + w - 10 - rsw, y + 7, FG_FAINT, true);

            int rowY = y + 24;
            if (rowsToDraw == 0) {
                String msg = (sdA != null && sdA.fetchedAt == 0) || (sdB != null && sdB.fetchedAt == 0)
                    ? "loading…" : "neither player ranked here";
                ctx.drawString(this.font,
                    Component.literal(msg).withStyle(ChatFormatting.DARK_GRAY),
                    x + 10, rowY + 2, 0x808080, true);
            } else {
                boolean alt = false;
                for (String mode : allModes) {
                    Ranking rA = sdA == null ? null : sdA.rankings.get(mode);
                    Ranking rB = sdB == null ? null : sdB.rankings.get(mode);
                    boolean hasA = rA != null && rA.tierLevel > 0;
                    boolean hasB = rB != null && rB.tierLevel > 0;
                    if (!hasA && !hasB) continue;

                    int sA = hasA ? rA.score() : -1;
                    int sB = hasB ? rB.score() : -1;
                    char winner;
                    if (sA > sB)      { winner = 'A'; wins1++; }
                    else if (sB > sA) { winner = 'B'; wins2++; }
                    else              { winner = '='; ties++; }

                    renderCmpRow(ctx, mode, rA, rB, winner, x + 10, rowY, w - 20, alt);
                    rowY += ROW_H;
                    alt = !alt;
                }
            }
            y += cardH + CARD_GAP;
        }

        y += 4;
        fillRect(ctx, x, y, x + w, y + 22, BG_HEADER);
        outlineRect(ctx, x, y, w, 22, 0xFF2A2F38);
        MutableComponent sum = Component.literal("Wins  ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(nameA + ": ").withStyle(ChatFormatting.WHITE))
            .append(Component.literal(String.valueOf(wins1)).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
            .append(Component.literal("    " + nameB + ": ").withStyle(ChatFormatting.WHITE))
            .append(Component.literal(String.valueOf(wins2)).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
            .append(Component.literal("    ties: ").withStyle(ChatFormatting.WHITE))
            .append(Component.literal(String.valueOf(ties)).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
        ctx.drawCenteredString(this.font, sum, x + w / 2, y + 7, 0xFFFFFF);
        return y + 26;
    }

    private void renderCmpRow(GuiGraphics ctx, String mode, Ranking rA, Ranking rB,
                              char winner, int x, int y, int w, boolean alt) {
        if (alt) fillRect(ctx, x - 4, y, x + w + 4, y + ROW_H, 0x14FFFFFF);

        int textX = x;
        try {
            ResourceLocation id = ResourceLocation.tryParse(TierIcons.iconFor(mode));
            if (id != null) {
                Item item = Compat.lookupItem(id);
                ItemStack stack = item == null ? ItemStack.EMPTY : new ItemStack(item);
                if (!stack.isEmpty()) {
                    ctx.renderItem(stack, x, y - 1);
                    textX = x + 20;
                }
            }
        } catch (Throwable ignored) {}

        String label = TierIcons.labelFor(mode);
        ctx.drawString(this.font,
            Component.literal(label).withColor(FG_TEXT),
            textX, y + 3, FG_TEXT, true);

        int rightStart = x + (w * 50 / 100);
        int rightEnd   = x + w;
        int colW       = (rightEnd - rightStart) / 3;

        Component tA = tierComp(rA);
        int wA = this.font.width(tA);
        int aCx = rightStart + colW / 2;
        int aColor = (rA != null && rA.tierLevel > 0) ? TierTaggerCore.argbFor(rA.label()) & 0xFFFFFF : 0x808080;
        if (winner == 'A') fillRect(ctx, aCx - wA / 2 - 3, y + 1, aCx + wA / 2 + 3, y + ROW_H - 1, 0x3000FF66);
        ctx.drawString(this.font, tA, aCx - wA / 2, y + 3, aColor, true);

        Component wsym;
        switch (winner) {
            case 'A': wsym = Component.literal("\u25C0").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD); break;
            case 'B': wsym = Component.literal("\u25B6").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD); break;
            default:  wsym = Component.literal("=").withStyle(ChatFormatting.YELLOW); break;
        }
        ctx.drawCenteredString(this.font, wsym, rightStart + colW + colW / 2, y + 3, 0xFFFFFF);

        Component tB = tierComp(rB);
        int wB = this.font.width(tB);
        int bCx = rightStart + colW * 2 + colW / 2;
        int bColor = (rB != null && rB.tierLevel > 0) ? TierTaggerCore.argbFor(rB.label()) & 0xFFFFFF : 0x808080;
        if (winner == 'B') fillRect(ctx, bCx - wB / 2 - 3, y + 1, bCx + wB / 2 + 3, y + ROW_H - 1, 0x3000FF66);
        ctx.drawString(this.font, tB, bCx - wB / 2, y + 3, bColor, true);
    }

    private static Component tierComp(Ranking r) {
        if (r == null || r.tierLevel <= 0) return Component.literal("—").withStyle(ChatFormatting.DARK_GRAY);
        return Component.literal(r.label()).withStyle(ChatFormatting.BOLD);
    }

    private static String regionPair(ServiceData a, ServiceData b) {
        String ra = (a == null || a.region == null || a.region.isBlank()) ? "—" : a.region;
        String rb = (b == null || b.region == null || b.region.isBlank()) ? "—" : b.region;
        return ra + "  vs  " + rb;
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

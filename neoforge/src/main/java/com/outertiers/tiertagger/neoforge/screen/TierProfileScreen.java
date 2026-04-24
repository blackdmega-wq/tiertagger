package com.outertiers.tiertagger.neoforge.screen;

import com.outertiers.tiertagger.common.PlayerData;
import com.outertiers.tiertagger.common.Ranking;
import com.outertiers.tiertagger.common.ServiceData;
import com.outertiers.tiertagger.common.TierConfig;
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

/** MCTiers-style profile screen — 2×2 grid of service panels. */
public class TierProfileScreen extends Screen {

    private static final ResourceLocation STEVE =
        ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png");

    private final Screen parent;
    private final String username;

    public TierProfileScreen(Screen parent, String username) {
        super(Component.literal("TierTagger – " + (username == null ? "?" : username)));
        this.parent   = parent;
        this.username = username == null ? "" : username;
    }

    @Override
    protected void init() {
        try { TierTaggerCore.cache().peekData(username); } catch (Throwable ignored) {}

        int btnY = this.height - 28;
        this.addRenderableWidget(Button.builder(Component.literal("Update"),
                btn -> {
                    try {
                        TierTaggerCore.cache().invalidatePlayer(username);
                        TierTaggerCore.cache().peekData(username);
                    } catch (Throwable ignored) {}
                })
            .bounds(20, btnY, 80, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Close"),
                btn -> closeSafely())
            .bounds(this.width - 100, btnY, 80, 20).build());
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        try {
            super.render(ctx, mouseX, mouseY, delta);

            Optional<PlayerData> opt = TierTaggerCore.cache().peekData(username);
            if (opt.isEmpty()) {
                ctx.drawCenteredString(this.font,
                    Component.literal("Loading…").withStyle(ChatFormatting.GRAY),
                    this.width / 2, this.height / 2, 0xAAAAAA);
                return;
            }

            PlayerData data = opt.get();
            renderHeader(ctx, data);
            renderServiceGrid(ctx, data);
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] profile screen render failed: {}", t.toString());
            try {
                ctx.drawCenteredString(this.font,
                    Component.literal("Error rendering profile — see log").withStyle(ChatFormatting.RED),
                    this.width / 2, 40, 0xFF5555);
            } catch (Throwable ignored) {}
        }
    }

    // ----------------- header -----------------

    private void renderHeader(GuiGraphics ctx, PlayerData data) {
        int cx = this.width / 2;
        int headSize = 48;
        int headX = cx - headSize / 2;
        int headY = 20;

        drawHead(ctx, data.username, headX, headY, headSize);

        MutableComponent name = Component.literal(data.username).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD);
        ctx.drawCenteredString(this.font, name, cx, headY + headSize + 6, 0xFFFFFFFF);

        TierConfig cfg = TierTaggerCore.config();
        if (cfg == null) return;
        TierService leftSvc  = cfg.leftServiceEnum();
        TierService rightSvc = cfg.rightServiceEnum();
        Ranking leftR  = data.get(leftSvc).highest();
        Ranking rightR = data.get(rightSvc).highest();

        int badgeY = headY + headSize / 2 - 6;
        if (leftR != null) {
            String label = leftR.label();
            int color = TierTaggerCore.argbFor(label);
            String txt = "[" + label + "]";
            int w = this.font.width(txt) + 4;
            ctx.fill(headX - w - 8, badgeY - 2, headX - 4, badgeY + 11, 0xC0000000);
            ctx.drawString(this.font,
                Component.literal(txt).withColor(color), headX - w - 6, badgeY + 1, color);
        }
        if (rightR != null && cfg.rightBadgeEnabled) {
            String label = rightR.label();
            int color = TierTaggerCore.argbFor(label);
            String txt = "[" + label + "]";
            ctx.fill(headX + headSize + 4, badgeY - 2,
                     headX + headSize + 8 + this.font.width(txt) + 4, badgeY + 11,
                     0xC0000000);
            ctx.drawString(this.font,
                Component.literal(txt).withColor(color), headX + headSize + 6, badgeY + 1, color);
        }
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

    // ----------------- grid -----------------

    private void renderServiceGrid(GuiGraphics ctx, PlayerData data) {
        int gridTop = 90;
        int gridBottom = this.height - 40;
        int margin = 16;

        TierService[] all = TierService.values();
        int cols = (this.width >= 720) ? 4 : 2;
        int rows = (int) Math.ceil(all.length / (double) cols);

        int cellW = (this.width - margin * 2) / cols - 4;
        int cellH = Math.max(120, (gridBottom - gridTop) / rows - 6);
        cellW = Math.min(cellW, 200);

        int totalW = cellW * cols + 6 * (cols - 1);
        int startX = (this.width - totalW) / 2;

        for (int i = 0; i < all.length; i++) {
            int row = i / cols;
            int col = i % cols;
            int x = startX + col * (cellW + 6);
            int y = gridTop + row * (cellH + 6);
            renderServicePanel(ctx, all[i], data.get(all[i]), x, y, cellW, cellH);
        }
    }

    private void renderServicePanel(GuiGraphics ctx, TierService svc, ServiceData sd,
                                    int x, int y, int w, int h) {
        ctx.fill(x, y, x + w, y + h, 0xCC101418);
        ctx.fill(x, y, x + w, y + 16, 0xFF1F242C);
        ctx.fill(x, y, x + 2, y + h, svc.accentArgb);
        ctx.renderOutline(x, y, w, h, 0xFF000000);

        ctx.drawString(this.font,
            Component.literal(svc.shortLabel).withColor(svc.accentArgb).copy().withStyle(ChatFormatting.BOLD),
            x + 6, y + 4, svc.accentArgb);
        ctx.drawString(this.font,
            Component.literal(svc.displayName).withStyle(ChatFormatting.WHITE),
            x + 6 + this.font.width(svc.shortLabel) + 4, y + 4, 0xFFFFFFFF);

        int statusY = y + 18;
        if (sd.fetchedAt == 0L) {
            ctx.drawString(this.font,
                Component.literal("Loading…").withStyle(ChatFormatting.DARK_GRAY),
                x + 6, statusY, 0x888888);
        } else if (sd.missing) {
            ctx.drawString(this.font,
                Component.literal("No data").withStyle(ChatFormatting.DARK_GRAY),
                x + 6, statusY, 0x888888);
        } else {
            String region = (sd.region == null || sd.region.isBlank()) ? "—" : sd.region;
            String rank   = sd.overall <= 0 ? "—" : ("#" + sd.overall);
            ctx.drawString(this.font,
                Component.literal(region).withStyle(ChatFormatting.AQUA),
                x + 6, statusY, 0x55FFFF);
            ctx.drawString(this.font,
                Component.literal(rank).withStyle(ChatFormatting.GOLD),
                x + w - 6 - this.font.width(rank), statusY, 0xFFAA00);
        }

        int rowY = y + 32;
        int rowH = 14;
        int maxRows = Math.max(1, (y + h - rowY - 6) / rowH);
        int drawn = 0;

        for (String mode : svc.modes) {
            if (drawn >= maxRows) break;
            Ranking r = sd.rankings.get(mode);
            renderTierRow(ctx, mode, r, x + 4, rowY + drawn * rowH, w - 8);
            drawn++;
        }
    }

    private void renderTierRow(GuiGraphics ctx, String mode, Ranking r, int x, int y, int w) {
        TierConfig cfg = TierTaggerCore.config();
        boolean showIcon = cfg == null ? true : (cfg.showModeIcon && !cfg.disableIcons);

        int textX = x;
        if (showIcon) {
            try {
                ResourceLocation id = ResourceLocation.tryParse(TierIcons.iconFor(mode));
                if (id != null) {
                    Item item = Compat.lookupItem(id);
                    ItemStack stack = item == null ? ItemStack.EMPTY : new ItemStack(item);
                    if (!stack.isEmpty()) {
                        ctx.pose().pushPose();
                        ctx.pose().translate(x, y - 1, 0);
                        ctx.pose().scale(0.625f, 0.625f, 1f);
                        ctx.renderItem(stack, 0, 0);
                        ctx.pose().popPose();
                        textX = x + 12;
                    }
                }
            } catch (Throwable ignored) {}
        }

        String label = TierIcons.labelFor(mode);
        ctx.drawString(this.font,
            Component.literal(label).withStyle(ChatFormatting.GRAY),
            textX, y + 1, 0xCCCCCC);

        if (cfg != null && cfg.disableTiers) return;

        Component valueText;
        if (r == null || r.tierLevel <= 0) {
            valueText = Component.literal("—").withStyle(ChatFormatting.DARK_GRAY);
        } else {
            String cur = r.label();
            int curColor = TierTaggerCore.argbFor(cur);
            MutableComponent t = Component.literal(cur).withColor(curColor).copy().withStyle(ChatFormatting.BOLD);
            if (r.peakDiffers()) {
                String peak = r.peakLabel();
                int peakColor = TierTaggerCore.argbFor(peak);
                t = t.append(Component.literal(" (").withStyle(ChatFormatting.DARK_GRAY))
                     .append(Component.literal(peak).withColor(peakColor))
                     .append(Component.literal(")").withStyle(ChatFormatting.DARK_GRAY));
            }
            if (r.retired) {
                t = Component.literal("(").withStyle(ChatFormatting.DARK_GRAY).append(t)
                     .append(Component.literal(")").withStyle(ChatFormatting.DARK_GRAY));
            }
            valueText = t;
        }
        int vw = this.font.width(valueText);
        ctx.drawString(this.font, valueText, x + w - vw, y + 1, 0xFFFFFFFF);
    }

    private void closeSafely() {
        Minecraft mc = (this.minecraft != null) ? this.minecraft : Minecraft.getInstance();
        if (mc != null) mc.setScreen(parent);
    }

    @Override
    public void onClose() { closeSafely(); }
}

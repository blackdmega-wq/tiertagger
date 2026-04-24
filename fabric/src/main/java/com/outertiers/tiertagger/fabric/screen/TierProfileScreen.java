package com.outertiers.tiertagger.fabric.screen;

import com.outertiers.tiertagger.common.PlayerData;
import com.outertiers.tiertagger.common.Ranking;
import com.outertiers.tiertagger.common.ServiceData;
import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierIcons;
import com.outertiers.tiertagger.common.TierService;
import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.fabric.compat.Compat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.Optional;

/**
 * MCTiers-style profile screen — four service columns, each with its own
 * region badge, overall rank, and per-mode tier grid.
 *
 * Layout (2×2 grid of service panels so it fits comfortably even on a 720p
 * window):
 *   ┌──────────────────────────────────────────┐
 *   │   [skin]   Outversal   [LEFT-tier]       │
 *   │                                          │
 *   │  ┌─MCTiers─────┐  ┌─OuterTiers──┐         │
 *   │  │  region #N  │  │  region #N  │         │
 *   │  │  mode  tier │  │  mode  tier │         │
 *   │  └─────────────┘  └─────────────┘         │
 *   │  ┌─PvPTiers────┐  ┌─SubTiers────┐         │
 *   │  └─────────────┘  └─────────────┘         │
 *   │  [Update]                          [Close]│
 *   └──────────────────────────────────────────┘
 *
 * Defensive: every render path is wrapped so a malformed cache entry can
 * never crash the client.
 */
public class TierProfileScreen extends Screen {

    private static final Identifier STEVE = Identifier.ofVanilla("textures/entity/player/wide/steve.png");

    private final Screen parent;
    private final String username;
    private boolean bgApplied = false;

    public TierProfileScreen(Screen parent, String username) {
        super(Text.literal("TierTagger – " + (username == null ? "?" : username)));
        this.parent   = parent;
        this.username = username == null ? "" : username;
    }

    @Override
    protected void init() {
        try { TierTaggerCore.cache().peekData(username); } catch (Throwable ignored) {}

        int btnY = this.height - 28;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Update"),
                btn -> {
                    try {
                        TierTaggerCore.cache().invalidatePlayer(username);
                        TierTaggerCore.cache().peekData(username);
                    } catch (Throwable ignored) {}
                })
            .dimensions(20, btnY, 80, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"),
                btn -> closeSafely())
            .dimensions(this.width - 100, btnY, 80, 20).build());
    }

    // Blur-safe guard
    @Override
    protected void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (bgApplied) return;
        bgApplied = true;
        super.renderBackground(ctx, mouseX, mouseY, delta);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        bgApplied = false;
        try {
            this.renderBackground(ctx, mouseX, mouseY, delta);
            super.render(ctx, mouseX, mouseY, delta);

            Optional<PlayerData> opt = TierTaggerCore.cache().peekData(username);
            if (opt.isEmpty()) {
                ctx.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("Loading…").formatted(Formatting.GRAY),
                    this.width / 2, this.height / 2, 0xAAAAAA);
                return;
            }

            PlayerData data = opt.get();
            renderHeader(ctx, data);
            renderServiceGrid(ctx, data);
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] profile screen render failed: {}", t.toString());
            try {
                ctx.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("Error rendering profile — see log").formatted(Formatting.RED),
                    this.width / 2, 40, 0xFF5555);
            } catch (Throwable ignored) {}
        }
    }

    // ----------------- header (skin + name + side badges) -----------------

    private void renderHeader(DrawContext ctx, PlayerData data) {
        int cx = this.width / 2;
        int headSize = 48;
        int headX = cx - headSize / 2;
        int headY = 20;

        drawHead(ctx, data.username, headX, headY, headSize);

        // Username under the head
        MutableText name = Text.literal(data.username).formatted(Formatting.WHITE, Formatting.BOLD);
        ctx.drawCenteredTextWithShadow(this.textRenderer, name, cx, headY + headSize + 6, 0xFFFFFFFF);

        // Left & right side badges = highest tier from the configured left/right service
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
            int w = this.textRenderer.getWidth(txt) + 4;
            ctx.fill(headX - w - 8, badgeY - 2, headX - 4, badgeY + 11, 0xC0000000);
            ctx.drawTextWithShadow(this.textRenderer,
                Text.literal(txt).withColor(color), headX - w - 6, badgeY + 1, color);
        }
        if (rightR != null && cfg.rightBadgeEnabled) {
            String label = rightR.label();
            int color = TierTaggerCore.argbFor(label);
            String txt = "[" + label + "]";
            ctx.fill(headX + headSize + 4, badgeY - 2,
                     headX + headSize + 8 + this.textRenderer.getWidth(txt) + 4, badgeY + 11,
                     0xC0000000);
            ctx.drawTextWithShadow(this.textRenderer,
                Text.literal(txt).withColor(color), headX + headSize + 6, badgeY + 1, color);
        }
    }

    private void drawHead(DrawContext ctx, String name, int x, int y, int size) {
        SkinTextures st = null;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.getNetworkHandler() != null) {
                PlayerListEntry e = mc.getNetworkHandler().getPlayerListEntry(name);
                if (e != null) st = e.getSkinTextures();
            }
        } catch (Throwable ignored) {}

        try {
            // Background plate behind the head for contrast
            ctx.fill(x - 2, y - 2, x + size + 2, y + size + 2, 0xFF1A1A1A);
            Compat.drawPlayerFace(ctx, st, STEVE, x, y, size);
        } catch (Throwable t) {
            // Fallback: solid steve-coloured square so we still have *something*
            ctx.fill(x, y, x + size, y + size, 0xFF6E4A2A);
        }
    }

    // ----------------- service grid (2×2 layout) -----------------

    private void renderServiceGrid(DrawContext ctx, PlayerData data) {
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

    private void renderServicePanel(DrawContext ctx, TierService svc, ServiceData sd,
                                    int x, int y, int w, int h) {
        // Panel chrome
        ctx.fill(x, y, x + w, y + h, 0xCC101418);
        ctx.fill(x, y, x + w, y + 16, 0xFF1F242C);
        // Accent strip on the left
        ctx.fill(x, y, x + 2, y + h, svc.accentArgb);
        // Border
        ctx.drawBorder(x, y, w, h, 0xFF000000);

        // Header: short label + service name
        ctx.drawTextWithShadow(this.textRenderer,
            Text.literal(svc.shortLabel).withColor(svc.accentArgb).copy().formatted(Formatting.BOLD),
            x + 6, y + 4, svc.accentArgb);
        ctx.drawTextWithShadow(this.textRenderer,
            Text.literal(svc.displayName).formatted(Formatting.WHITE),
            x + 6 + this.textRenderer.getWidth(svc.shortLabel) + 4, y + 4, 0xFFFFFFFF);

        // Status row: region + overall rank
        int statusY = y + 18;
        if (sd.fetchedAt == 0L) {
            ctx.drawTextWithShadow(this.textRenderer,
                Text.literal("Loading…").formatted(Formatting.DARK_GRAY),
                x + 6, statusY, 0x888888);
        } else if (sd.missing) {
            ctx.drawTextWithShadow(this.textRenderer,
                Text.literal("No data").formatted(Formatting.DARK_GRAY),
                x + 6, statusY, 0x888888);
        } else {
            String region = (sd.region == null || sd.region.isBlank()) ? "—" : sd.region;
            String rank   = sd.overall <= 0 ? "—" : ("#" + sd.overall);
            ctx.drawTextWithShadow(this.textRenderer,
                Text.literal(region).formatted(Formatting.AQUA),
                x + 6, statusY, 0x55FFFF);
            ctx.drawTextWithShadow(this.textRenderer,
                Text.literal(rank).formatted(Formatting.GOLD),
                x + w - 6 - this.textRenderer.getWidth(rank), statusY, 0xFFAA00);
        }

        // Tier rows
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

    private void renderTierRow(DrawContext ctx, String mode, Ranking r, int x, int y, int w) {
        TierConfig cfg = TierTaggerCore.config();
        boolean showIcon = cfg == null ? true : (cfg.showModeIcon && !cfg.disableIcons);

        int textX = x;
        if (showIcon) {
            try {
                Identifier id = Identifier.tryParse(TierIcons.iconFor(mode));
                if (id != null) {
                    Item item = Compat.lookupItem(id);
                    ItemStack stack = item == null ? ItemStack.EMPTY : new ItemStack(item);
                    if (!stack.isEmpty()) {
                        // Render ~10x10 by drawing at half-scale via matrix push.
                        ctx.getMatrices().push();
                        ctx.getMatrices().translate(x, y - 1, 0);
                        ctx.getMatrices().scale(0.625f, 0.625f, 1f); // 16 * 0.625 = 10
                        ctx.drawItem(stack, 0, 0);
                        ctx.getMatrices().pop();
                        textX = x + 12;
                    }
                }
            } catch (Throwable ignored) {}
        }

        String label = TierIcons.labelFor(mode);
        ctx.drawTextWithShadow(this.textRenderer,
            Text.literal(label).formatted(Formatting.GRAY),
            textX, y + 1, 0xCCCCCC);

        if (cfg != null && cfg.disableTiers) return;

        Text valueText;
        if (r == null || r.tierLevel <= 0) {
            valueText = Text.literal("—").formatted(Formatting.DARK_GRAY);
        } else {
            String cur = r.label();
            int curColor = TierTaggerCore.argbFor(cur);
            MutableText t = Text.literal(cur).withColor(curColor).copy().formatted(Formatting.BOLD);
            if (r.peakDiffers()) {
                String peak = r.peakLabel();
                int peakColor = TierTaggerCore.argbFor(peak);
                t = t.append(Text.literal(" (").formatted(Formatting.DARK_GRAY))
                     .append(Text.literal(peak).withColor(peakColor))
                     .append(Text.literal(")").formatted(Formatting.DARK_GRAY));
            }
            if (r.retired) {
                t = Text.literal("(").formatted(Formatting.DARK_GRAY).append(t)
                     .append(Text.literal(")").formatted(Formatting.DARK_GRAY));
            }
            valueText = t;
        }
        int vw = this.textRenderer.getWidth(valueText);
        ctx.drawTextWithShadow(this.textRenderer, valueText, x + w - vw, y + 1, 0xFFFFFFFF);
    }

    private void closeSafely() {
        MinecraftClient mc = (this.client != null) ? this.client : MinecraftClient.getInstance();
        if (mc != null) mc.setScreen(parent);
    }

    @Override
    public void close() { closeSafely(); }
}

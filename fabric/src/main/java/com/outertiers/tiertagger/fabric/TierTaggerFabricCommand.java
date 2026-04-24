package com.outertiers.tiertagger.fabric;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.outertiers.tiertagger.common.PlayerData;
import com.outertiers.tiertagger.common.Ranking;
import com.outertiers.tiertagger.common.ServiceData;
import com.outertiers.tiertagger.common.TierCache;
import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierService;
import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.fabric.screen.TierConfigScreen;
import com.outertiers.tiertagger.fabric.screen.TierCompareScreen;
import com.outertiers.tiertagger.fabric.screen.TierLookupScreen;
import com.outertiers.tiertagger.fabric.screen.TierProfileScreen;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class TierTaggerFabricCommand {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("tiertagger")
                .then(ClientCommandManager.literal("help").executes(c -> { sendHelp(c.getSource()); return 1; }))
                .then(ClientCommandManager.literal("version").executes(c -> {
                    c.getSource().sendFeedback(Text.literal("§a[TierTagger] §rversion §e" + TierTaggerCore.MOD_VERSION));
                    return 1;
                }))
                .then(ClientCommandManager.literal("status").executes(c -> { sendStatus(c.getSource()); return 1; }))
                .then(ClientCommandManager.literal("reload").executes(c -> {
                    TierConfig fresh = TierConfig.load();
                    copyConfig(fresh, TierTaggerCore.config());
                    TierTaggerCore.cache().invalidate();
                    c.getSource().sendFeedback(Text.literal("§a[TierTagger] §rConfig reloaded from disk."));
                    return 1;
                }))
                .then(ClientCommandManager.literal("reset").executes(c -> {
                    TierTaggerCore.config().resetToDefaults();
                    TierTaggerCore.config().save();
                    TierTaggerCore.cache().invalidate();
                    c.getSource().sendFeedback(Text.literal("§a[TierTagger] §rConfig reset to defaults."));
                    return 1;
                }))
                .then(ClientCommandManager.literal("ttl")
                    .then(ClientCommandManager.argument("seconds", IntegerArgumentType.integer(10, 86400))
                        .executes(c -> {
                            int s = IntegerArgumentType.getInteger(c, "seconds");
                            TierTaggerCore.config().cacheTtlSeconds = s;
                            TierTaggerCore.config().save();
                            c.getSource().sendFeedback(Text.literal("§a[TierTagger] §rCache TTL set to §e" + s + "s§r."));
                            return 1;
                        })))
                .then(ClientCommandManager.literal("color").executes(c -> {
                    TierTaggerCore.config().coloredBadges = !TierTaggerCore.config().coloredBadges;
                    TierTaggerCore.config().save();
                    c.getSource().sendFeedback(Text.literal("§a[TierTagger] §rColoured badges: " +
                        (TierTaggerCore.config().coloredBadges ? "§aon" : "§coff")));
                    return 1;
                }))
                .then(ClientCommandManager.literal("format")
                    .then(ClientCommandManager.argument("style", StringArgumentType.word())
                        .suggests((ctx, b) -> { for (String f : TierConfig.BADGE_FORMATS) b.suggest(f); return b.buildFuture(); })
                        .executes(c -> {
                            String f = StringArgumentType.getString(c, "style").toLowerCase();
                            if (!TierConfig.isValidBadgeFormat(f)) {
                                c.getSource().sendError(Text.literal("Unknown format: " + f + " (use bracket|plain|short)"));
                                return 0;
                            }
                            TierTaggerCore.config().badgeFormat = f;
                            TierTaggerCore.config().save();
                            c.getSource().sendFeedback(Text.literal("§a[TierTagger] §rBadge format: §e" + f));
                            return 1;
                        })))
                .then(ClientCommandManager.literal("lookup")
                .then(ClientCommandManager.argument("player", StringArgumentType.word())
                    .executes(c -> {
                        String name = StringArgumentType.getString(c, "player");
                        try { TierTaggerCore.cache().peekData(name); } catch (Throwable ignored) {}
                        PendingScreen.open(new TierLookupScreen(null, name));
                        c.getSource().sendFeedback(Text.literal("§7[TierTagger] §rLooking up §e" + name + "§r…"));
                        return 1;
                    })))
                .then(ClientCommandManager.literal("compare")
                    .then(ClientCommandManager.argument("player1", StringArgumentType.word())
                        .then(ClientCommandManager.argument("player2", StringArgumentType.word())
                            .executes(c -> {
                                String n1 = StringArgumentType.getString(c, "player1");
                                String n2 = StringArgumentType.getString(c, "player2");
                                try { TierTaggerCore.cache().peekData(n1); TierTaggerCore.cache().peekData(n2); } catch (Throwable ignored) {}
                                PendingScreen.open(new TierCompareScreen(null, n1, n2));
                                c.getSource().sendFeedback(Text.literal("§7[TierTagger] §rComparing §e" + n1 + " §rvs §e" + n2 + "§r…"));
                                return 1;
                            })
                            .then(ClientCommandManager.argument("tierlist", StringArgumentType.word())
                                .suggests((ctx, b) -> {
                                    for (TierService s : TierService.values()) b.suggest(s.id);
                                    b.suggest("all");
                                    return b.buildFuture();
                                })
                                .executes(c -> {
                                    String cn1 = StringArgumentType.getString(c, "player1");
                                    String cn2 = StringArgumentType.getString(c, "player2");
                                    try { TierTaggerCore.cache().peekData(cn1); TierTaggerCore.cache().peekData(cn2); } catch (Throwable ignored) {}
                                    PendingScreen.open(new TierCompareScreen(null, cn1, cn2));
                                    c.getSource().sendFeedback(Text.literal("§7[TierTagger] §rComparing §e" + cn1 + " §rvs §e" + cn2 + "§r…"));
                                    return 1;
                                })))))
                .then(ClientCommandManager.literal("clear")
                    .then(ClientCommandManager.argument("player", StringArgumentType.word())
                        .executes(c -> {
                            String name = StringArgumentType.getString(c, "player");
                            TierTaggerCore.cache().invalidatePlayer(name);
                            c.getSource().sendFeedback(Text.literal("§a[TierTagger] §rCleared cache for §e" + name));
                            return 1;
                        })))
                .then(ClientCommandManager.literal("service")
                    .then(ClientCommandManager.literal("left")
                        .then(ClientCommandManager.argument("name", StringArgumentType.word())
                            .suggests((ctx, b) -> { for (TierService s : TierService.values()) b.suggest(s.id); return b.buildFuture(); })
                            .executes(c -> {
                                String s = StringArgumentType.getString(c, "name");
                                TierService svc = TierService.byId(s);
                                if (svc == null) { c.getSource().sendError(Text.literal("Unknown service: " + s)); return 0; }
                                TierTaggerCore.config().leftService = svc.id;
                                TierTaggerCore.config().save();
                                c.getSource().sendFeedback(Text.literal("§a[TierTagger] §rLeft badge: §e" + svc.displayName));
                                return 1;
                            })))
                    .then(ClientCommandManager.literal("right")
                        .then(ClientCommandManager.argument("name", StringArgumentType.word())
                            .suggests((ctx, b) -> { for (TierService s : TierService.values()) b.suggest(s.id); return b.buildFuture(); })
                            .executes(c -> {
                                String s = StringArgumentType.getString(c, "name");
                                TierService svc = TierService.byId(s);
                                if (svc == null) { c.getSource().sendError(Text.literal("Unknown service: " + s)); return 0; }
                                TierTaggerCore.config().rightService = svc.id;
                                TierTaggerCore.config().save();
                                c.getSource().sendFeedback(Text.literal("§a[TierTagger] §rRight badge: §e" + svc.displayName));
                                return 1;
                            })))
                    .then(ClientCommandManager.literal("toggle")
                        .then(ClientCommandManager.argument("name", StringArgumentType.word())
                            .suggests((ctx, b) -> { for (TierService s : TierService.values()) b.suggest(s.id); return b.buildFuture(); })
                            .executes(c -> {
                                String s = StringArgumentType.getString(c, "name");
                                TierService svc = TierService.byId(s);
                                if (svc == null) { c.getSource().sendError(Text.literal("Unknown service: " + s)); return 0; }
                                boolean cur = TierTaggerCore.config().isServiceEnabled(svc);
                                TierTaggerCore.config().setServiceEnabled(svc, !cur);
                                TierTaggerCore.config().save();
                                c.getSource().sendFeedback(Text.literal("§a[TierTagger] §r" + svc.displayName + ": " +
                                    (!cur ? "§aon" : "§coff")));
                                return 1;
                            }))))
                .then(ClientCommandManager.literal("toggle").executes(ctx -> {
                    TierTaggerCore.config().showInTab = !TierTaggerCore.config().showInTab;
                    TierTaggerCore.config().save();
                    ctx.getSource().sendFeedback(Text.literal("§a[TierTagger] §rTab badges: " +
                        (TierTaggerCore.config().showInTab ? "§aon" : "§cdisabled")));
                    return 1;
                }))
                .then(ClientCommandManager.literal("nametag").executes(ctx -> {
                    TierTaggerCore.config().showNametag = !TierTaggerCore.config().showNametag;
                    TierTaggerCore.config().save();
                    ctx.getSource().sendFeedback(Text.literal("§a[TierTagger] §rNametag badges: " +
                        (TierTaggerCore.config().showNametag ? "§aon" : "§cdisabled")));
                    return 1;
                }))
                .then(ClientCommandManager.literal("peak").executes(ctx -> {
                    TierTaggerCore.config().showPeak = !TierTaggerCore.config().showPeak;
                    TierTaggerCore.config().save();
                    TierTaggerCore.cache().invalidate();
                    ctx.getSource().sendFeedback(Text.literal("§a[TierTagger] §rShow peak tier: " +
                        (TierTaggerCore.config().showPeak ? "§aon" : "§coff")));
                    return 1;
                }))
                .then(ClientCommandManager.literal("refresh").executes(ctx -> {
                    TierTaggerCore.cache().invalidate();
                    ctx.getSource().sendFeedback(Text.literal("§a[TierTagger] §rCache cleared."));
                    return 1;
                }))
                .then(ClientCommandManager.literal("config").executes(ctx -> {
                    PendingScreen.open(new TierConfigScreen(null));
                    ctx.getSource().sendFeedback(Text.literal("§7[TierTagger] §rOpening config…"));
                    return 1;
                }))
                .then(ClientCommandManager.literal("profile")
                    .then(ClientCommandManager.argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "player");
                            try { TierTaggerCore.cache().peekData(name); } catch (Throwable ignored) {}
                            PendingScreen.open(new TierProfileScreen(null, name));
                            ctx.getSource().sendFeedback(Text.literal("§7[TierTagger] §rOpening profile for §e" + name + "§r…"));
                            return 1;
                        })))
                .executes(ctx -> { sendStatus(ctx.getSource()); return 1; })
            );
        });
    }

    private static void copyConfig(TierConfig from, TierConfig to) {
        to.apiBase = from.apiBase;
        to.gamemode = from.gamemode;
        to.showInTab = from.showInTab;
        to.showNametag = from.showNametag;
        to.showPeak = from.showPeak;
        to.fallthroughToHighest = from.fallthroughToHighest;
        to.coloredBadges = from.coloredBadges;
        to.badgeFormat = from.badgeFormat;
        to.cacheTtlSeconds = from.cacheTtlSeconds;
        to.services = from.services;
        to.primaryService = from.primaryService;
        to.leftService = from.leftService;
        to.rightService = from.rightService;
        to.rightBadgeEnabled = from.rightBadgeEnabled;
        to.showServiceIcon = from.showServiceIcon;
        to.showModeIcon = from.showModeIcon;
        to.displayMode = from.displayMode;
        to.enabledModes = from.enabledModes;
        to.disableTiers = from.disableTiers;
        to.disableIcons = from.disableIcons;
        to.disableAnimations = from.disableAnimations;
    }

    // ---------- chat helpers ----------

    private static void sendLookup(FabricClientCommandSource src, String name) {
        Optional<PlayerData> opt = TierTaggerCore.cache().peekData(name);
        if (opt.isEmpty()) {
            src.sendFeedback(Text.literal("§7[TierTagger] §rFetching §e" + name + "§r…"));
            return;
        }
        PlayerData data = opt.get();
        src.sendFeedback(Text.literal("§6§l━━━━━━━━━ §f§lTier Lookup §6§l━━━━━━━━━"));
        src.sendFeedback(Text.literal(" §b§l" + name));
        src.sendFeedback(Text.literal("§8─────────────────────────────────────"));
        for (TierService svc : TierService.values()) {
            ServiceData sd = data.get(svc);
            String tier = TierTaggerCore.tierForService(data, svc);
            String region = sd.region == null ? "—" : sd.region;
            String tierStr = tier == null ? "§8unranked" : "§" + TierTaggerCore.colourCodeFor(tier) + "§l" + tier;
            String state;
            if (sd.fetchedAt == 0L)      state = "§7loading";
            else if (sd.missing)         state = "§8not listed";
            else                         state = tierStr + "§r §7(" + sd.rankedCount() + " modes, region " + region + ")";
            src.sendFeedback(Text.literal(String.format(" §f%-12s§r %s", svc.displayName, state)));
        }
    }

    private static void sendCompare(FabricClientCommandSource src, String n1, String n2, String tierlistArg) {
        Optional<PlayerData> e1 = TierTaggerCore.cache().peekData(n1);
        Optional<PlayerData> e2 = TierTaggerCore.cache().peekData(n2);
        if (e1.isEmpty() || e2.isEmpty()) {
            src.sendFeedback(Text.literal("§7[TierTagger] §rFetching tiers — try again in a moment…"));
            return;
        }

        // Resolve which tier-list(s) to compare. "all" or null/empty -> every service.
        String want = tierlistArg == null ? "all" : tierlistArg.toLowerCase(Locale.ROOT);
        TierService only = null;
        if (!want.equals("all") && !want.isBlank()) {
            only = TierService.byId(want);
            if (only == null) {
                src.sendError(Text.literal("Unknown tier list: " + tierlistArg +
                    " (valid: mctiers, outertiers, pvptiers, subtiers, all)"));
                return;
            }
        }

        PlayerData a = e1.get();
        PlayerData b = e2.get();
        String header = only == null ? "Tier Compare" : "Tier Compare – " + only.displayName;
        src.sendFeedback(Text.literal("§6§l━━━━━━━━━ §f§l" + header + " §6§l━━━━━━━━━"));
        src.sendFeedback(Text.literal(String.format(" §b§l%-12s §7vs §b§l%s", n1, n2)));
        src.sendFeedback(Text.literal("§8─────────────────────────────────────"));
        int wins1 = 0, wins2 = 0, ties = 0;
        for (TierService svc : TierService.values()) {
            if (only != null && svc != only) continue;
            ServiceData sa = a.get(svc);
            ServiceData sb = b.get(svc);
            for (String mode : svc.modes) {
                Ranking ra = sa.rankings.get(mode);
                Ranking rb = sb.rankings.get(mode);
                if ((ra == null || ra.tierLevel == 0) && (rb == null || rb.tierLevel == 0)) continue;
                int s1 = ra == null ? -1 : ra.score();
                int s2 = rb == null ? -1 : rb.score();
                String marker;
                if (s1 > s2) { marker = "§a◀"; wins1++; }
                else if (s2 > s1) { marker = "§a▶"; wins2++; }
                else { marker = "§e="; ties++; }
                String c1 = ra == null ? "§8unranked" : "§" + TierTaggerCore.colourCodeFor(ra.label()) + "§l" + ra.label();
                String c2 = rb == null ? "§8unranked" : "§" + TierTaggerCore.colourCodeFor(rb.label()) + "§l" + rb.label();
                src.sendFeedback(Text.literal(String.format(" §7%-3s§r §f%-9s §r%s §r %s§r %s§r",
                    svc.shortLabel, mode, c1, marker, c2)));
            }
        }
        src.sendFeedback(Text.literal("§8─────────────────────────────────────"));
        src.sendFeedback(Text.literal(String.format(" §7Wins: §a%s§7: §f%d  §7- §a%s§7: §f%d  §7(§e%d tie%s§7)",
            n1, wins1, n2, wins2, ties, ties == 1 ? "" : "s")));
    }

    private static void sendStatus(FabricClientCommandSource src) {
        TierConfig c = TierTaggerCore.config();
        StringBuilder svc = new StringBuilder();
        for (Map.Entry<String, Boolean> e : c.services.entrySet()) {
            svc.append("§e").append(e.getKey()).append("§7=")
                .append(e.getValue() ? "§aon" : "§coff").append("§r ");
        }
        src.sendFeedback(Text.literal(
            "§a[TierTagger] §rv" + TierTaggerCore.MOD_VERSION + "\n" +
            " §7display§r: §e" + c.displayMode + "\n" +
            " §7left badge§r: §e" + c.leftService + "\n" +
            " §7right badge§r: §e" + c.rightService + "  §7(enabled: " + (c.rightBadgeEnabled ? "§aon" : "§coff") + "§7)\n" +
            " §7tab§r: " + (c.showInTab ? "§aon" : "§coff") +
                "  §7nametag§r: " + (c.showNametag ? "§aon" : "§coff") +
                "  §7coloured§r: " + (c.coloredBadges ? "§aon" : "§coff") + "\n" +
            " §7services§r: " + svc.toString() + "\n" +
            " §7format§r: §e" + c.badgeFormat + "  §7TTL§r: §e" + c.cacheTtlSeconds + "s\n" +
            "§7Type §f/tiertagger help §7for the full command list."));
    }

    private static void sendHelp(FabricClientCommandSource src) {
        src.sendFeedback(Text.literal("§a===== §fTierTagger Commands §a=====").formatted(Formatting.RESET));
        String[][] rows = {
            {"/tiertagger",                  "Show current settings"},
            {"/tiertagger config",           "Open the GUI settings screen"},
            {"/tiertagger profile <player>", "Open the four-service tier breakdown"},
            {"/tiertagger lookup <player>",  "Print all-service tiers in chat"},
            {"/tiertagger compare <a> <b> [list]", "Side-by-side comparison (mctiers|outertiers|pvptiers|subtiers|all)"},
            {"/tiertagger service left <s>", "Set the LEFT badge service"},
            {"/tiertagger service right <s>","Set the RIGHT badge service"},
            {"/tiertagger service toggle <s>","Enable/disable a service"},
            {"/tiertagger toggle",           "Toggle tab list badges"},
            {"/tiertagger nametag",          "Toggle nametag badges"},
            {"/tiertagger peak",             "Toggle peak tier display"},
            {"/tiertagger color",            "Toggle coloured badges"},
            {"/tiertagger format <style>",   "bracket | plain | short"},
            {"/tiertagger ttl <seconds>",    "Set cache TTL"},
            {"/tiertagger refresh",          "Clear the cache"},
            {"/tiertagger clear <player>",   "Forget a single player"},
            {"/tiertagger reload",           "Reload config from disk"},
            {"/tiertagger reset",            "Reset config to defaults"},
        };
        for (String[] r : rows) src.sendFeedback(Text.literal("§e" + r[0] + " §8— §7" + r[1]));
        src.sendFeedback(Text.literal("§7Services: §emctiers §8/ §eoutertiers §8/ §epvptiers §8/ §esubtiers"));
    }
}

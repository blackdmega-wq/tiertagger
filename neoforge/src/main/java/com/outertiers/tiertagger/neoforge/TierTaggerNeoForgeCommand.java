package com.outertiers.tiertagger.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.outertiers.tiertagger.common.PlayerData;
import com.outertiers.tiertagger.common.Ranking;
import com.outertiers.tiertagger.common.ServiceData;
import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierService;
import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.neoforge.screen.TierConfigScreen;
import com.outertiers.tiertagger.neoforge.screen.TierProfileScreen;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class TierTaggerNeoForgeCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tiertagger")
            .then(Commands.literal("help").executes(c -> { sendHelp(c.getSource()); return 1; }))
            .then(Commands.literal("version").executes(c -> {
                c.getSource().sendSuccess(() -> Component.literal("§a[TierTagger] §rversion §e" + TierTaggerCore.MOD_VERSION), false);
                return 1;
            }))
            .then(Commands.literal("status").executes(c -> { sendStatus(c.getSource()); return 1; }))
            .then(Commands.literal("reload").executes(c -> {
                copyConfig(TierConfig.load(), TierTaggerCore.config());
                TierTaggerCore.cache().invalidate();
                c.getSource().sendSuccess(() -> Component.literal("§a[TierTagger] §rConfig reloaded from disk."), false);
                return 1;
            }))
            .then(Commands.literal("reset").executes(c -> {
                TierTaggerCore.config().resetToDefaults();
                TierTaggerCore.config().save();
                TierTaggerCore.cache().invalidate();
                c.getSource().sendSuccess(() -> Component.literal("§a[TierTagger] §rConfig reset to defaults."), false);
                return 1;
            }))
            .then(Commands.literal("ttl")
                .then(Commands.argument("seconds", IntegerArgumentType.integer(10, 86400))
                    .executes(c -> {
                        int s = IntegerArgumentType.getInteger(c, "seconds");
                        TierTaggerCore.config().cacheTtlSeconds = s;
                        TierTaggerCore.config().save();
                        c.getSource().sendSuccess(() -> Component.literal("§a[TierTagger] §rCache TTL: §e" + s + "s"), false);
                        return 1;
                    })))
            .then(Commands.literal("color").executes(c -> {
                TierTaggerCore.config().coloredBadges = !TierTaggerCore.config().coloredBadges;
                TierTaggerCore.config().save();
                boolean on = TierTaggerCore.config().coloredBadges;
                c.getSource().sendSuccess(() -> Component.literal("§a[TierTagger] §rColoured badges: " + (on ? "§aon" : "§coff")), false);
                return 1;
            }))
            .then(Commands.literal("format")
                .then(Commands.argument("style", StringArgumentType.word())
                    .suggests((ctx, b) -> { for (String f : TierConfig.BADGE_FORMATS) b.suggest(f); return b.buildFuture(); })
                    .executes(c -> {
                        String f = StringArgumentType.getString(c, "style").toLowerCase();
                        if (!TierConfig.isValidBadgeFormat(f)) {
                            c.getSource().sendFailure(Component.literal("Unknown format: " + f + " (use bracket|plain|short)"));
                            return 0;
                        }
                        TierTaggerCore.config().badgeFormat = f;
                        TierTaggerCore.config().save();
                        c.getSource().sendSuccess(() -> Component.literal("§a[TierTagger] §rBadge format: §e" + f), false);
                        return 1;
                    })))
            .then(Commands.literal("lookup")
                .then(Commands.argument("player", StringArgumentType.word())
                    .executes(c -> {
                        String name = StringArgumentType.getString(c, "player");
                        sendLookup(c.getSource(), name);
                        return 1;
                    })))
            .then(Commands.literal("compare")
                .then(Commands.argument("player1", StringArgumentType.word())
                    .then(Commands.argument("player2", StringArgumentType.word())
                        .executes(c -> {
                            sendCompare(c.getSource(),
                                StringArgumentType.getString(c, "player1"),
                                StringArgumentType.getString(c, "player2"),
                                "all");
                            return 1;
                        })
                        .then(Commands.argument("tierlist", StringArgumentType.word())
                            .suggests((ctx, b) -> {
                                for (TierService s : TierService.values()) b.suggest(s.id);
                                b.suggest("all");
                                return b.buildFuture();
                            })
                            .executes(c -> {
                                sendCompare(c.getSource(),
                                    StringArgumentType.getString(c, "player1"),
                                    StringArgumentType.getString(c, "player2"),
                                    StringArgumentType.getString(c, "tierlist"));
                                return 1;
                            })))))
            .then(Commands.literal("clear")
                .then(Commands.argument("player", StringArgumentType.word())
                    .executes(c -> {
                        String name = StringArgumentType.getString(c, "player");
                        TierTaggerCore.cache().invalidatePlayer(name);
                        c.getSource().sendSuccess(() -> Component.literal("§a[TierTagger] §rCleared cache for §e" + name), false);
                        return 1;
                    })))
            .then(Commands.literal("service")
                .then(Commands.literal("left")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests((ctx, b) -> { for (TierService s : TierService.values()) b.suggest(s.id); return b.buildFuture(); })
                        .executes(c -> {
                            String s = StringArgumentType.getString(c, "name");
                            TierService svc = TierService.byId(s);
                            if (svc == null) { c.getSource().sendFailure(Component.literal("Unknown service: " + s)); return 0; }
                            TierTaggerCore.config().leftService = svc.id;
                            TierTaggerCore.config().save();
                            c.getSource().sendSuccess(() -> Component.literal("§a[TierTagger] §rLeft badge: §e" + svc.displayName), false);
                            return 1;
                        })))
                .then(Commands.literal("right")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests((ctx, b) -> { for (TierService s : TierService.values()) b.suggest(s.id); return b.buildFuture(); })
                        .executes(c -> {
                            String s = StringArgumentType.getString(c, "name");
                            TierService svc = TierService.byId(s);
                            if (svc == null) { c.getSource().sendFailure(Component.literal("Unknown service: " + s)); return 0; }
                            TierTaggerCore.config().rightService = svc.id;
                            TierTaggerCore.config().save();
                            c.getSource().sendSuccess(() -> Component.literal("§a[TierTagger] §rRight badge: §e" + svc.displayName), false);
                            return 1;
                        })))
                .then(Commands.literal("toggle")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests((ctx, b) -> { for (TierService s : TierService.values()) b.suggest(s.id); return b.buildFuture(); })
                        .executes(c -> {
                            String s = StringArgumentType.getString(c, "name");
                            TierService svc = TierService.byId(s);
                            if (svc == null) { c.getSource().sendFailure(Component.literal("Unknown service: " + s)); return 0; }
                            boolean cur = TierTaggerCore.config().isServiceEnabled(svc);
                            TierTaggerCore.config().setServiceEnabled(svc, !cur);
                            TierTaggerCore.config().save();
                            c.getSource().sendSuccess(() -> Component.literal("§a[TierTagger] §r" + svc.displayName + ": " +
                                (!cur ? "§aon" : "§coff")), false);
                            return 1;
                        }))))
            .then(Commands.literal("toggle").executes(ctx -> {
                TierTaggerCore.config().showInTab = !TierTaggerCore.config().showInTab;
                TierTaggerCore.config().save();
                boolean on = TierTaggerCore.config().showInTab;
                ctx.getSource().sendSuccess(() -> Component.literal("§a[TierTagger] §rTab badges: " + (on ? "§aon" : "§cdisabled")), false);
                return 1;
            }))
            .then(Commands.literal("nametag").executes(ctx -> {
                TierTaggerCore.config().showNametag = !TierTaggerCore.config().showNametag;
                TierTaggerCore.config().save();
                boolean on = TierTaggerCore.config().showNametag;
                ctx.getSource().sendSuccess(() -> Component.literal("§a[TierTagger] §rNametag badges: " + (on ? "§aon" : "§cdisabled")), false);
                return 1;
            }))
            .then(Commands.literal("peak").executes(ctx -> {
                TierTaggerCore.config().showPeak = !TierTaggerCore.config().showPeak;
                TierTaggerCore.config().save();
                TierTaggerCore.cache().invalidate();
                boolean on = TierTaggerCore.config().showPeak;
                ctx.getSource().sendSuccess(() -> Component.literal("§a[TierTagger] §rShow peak tier: " + (on ? "§aon" : "§coff")), false);
                return 1;
            }))
            .then(Commands.literal("refresh").executes(ctx -> {
                TierTaggerCore.cache().invalidate();
                ctx.getSource().sendSuccess(() -> Component.literal("§a[TierTagger] §rCache cleared."), false);
                return 1;
            }))
            .then(Commands.literal("config").executes(ctx -> {
                PendingScreen.open(new TierConfigScreen(null));
                ctx.getSource().sendSuccess(() -> Component.literal("§7[TierTagger] §rOpening config…"), false);
                return 1;
            }))
            .then(Commands.literal("profile")
                .then(Commands.argument("player", StringArgumentType.word())
                    .executes(ctx -> {
                        String name = StringArgumentType.getString(ctx, "player");
                        try { TierTaggerCore.cache().peekData(name); } catch (Throwable ignored) {}
                        PendingScreen.open(new TierProfileScreen(null, name));
                        ctx.getSource().sendSuccess(() -> Component.literal("§7[TierTagger] §rOpening profile for §e" + name + "§r…"), false);
                        return 1;
                    })))
            .executes(ctx -> { sendStatus(ctx.getSource()); return 1; })
        );
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

    private static void sendLookup(CommandSourceStack src, String name) {
        Optional<PlayerData> opt = TierTaggerCore.cache().peekData(name);
        if (opt.isEmpty()) {
            src.sendSuccess(() -> Component.literal("§7[TierTagger] §rFetching §e" + name + "§r…"), false);
            return;
        }
        PlayerData data = opt.get();
        src.sendSuccess(() -> Component.literal("§6§l━━━━━━━━━ §f§lTier Lookup §6§l━━━━━━━━━"), false);
        src.sendSuccess(() -> Component.literal(" §b§l" + name), false);
        src.sendSuccess(() -> Component.literal("§8─────────────────────────────────────"), false);
        for (TierService svc : TierService.values()) {
            ServiceData sd = data.get(svc);
            String tier = TierTaggerCore.tierForService(data, svc);
            String region = sd.region == null ? "—" : sd.region;
            String tierStr = tier == null ? "§8unranked" : "§" + TierTaggerCore.colourCodeFor(tier) + "§l" + tier;
            String state;
            if (sd.fetchedAt == 0L)      state = "§7loading";
            else if (sd.missing)         state = "§8not listed";
            else                         state = tierStr + "§r §7(" + sd.rankedCount() + " modes, region " + region + ")";
            final String row = String.format(" §f%-12s§r %s", svc.displayName, state);
            src.sendSuccess(() -> Component.literal(row), false);
        }
    }

    private static void sendCompare(CommandSourceStack src, String n1, String n2, String tierlistArg) {
        Optional<PlayerData> e1 = TierTaggerCore.cache().peekData(n1);
        Optional<PlayerData> e2 = TierTaggerCore.cache().peekData(n2);
        if (e1.isEmpty() || e2.isEmpty()) {
            src.sendSuccess(() -> Component.literal("§7[TierTagger] §rFetching tiers — try again in a moment…"), false);
            return;
        }

        // Resolve which tier-list(s) to compare. "all" or null/empty -> every service.
        String want = tierlistArg == null ? "all" : tierlistArg.toLowerCase(Locale.ROOT);
        TierService only = null;
        if (!want.equals("all") && !want.isBlank()) {
            only = TierService.byId(want);
            if (only == null) {
                src.sendFailure(Component.literal("Unknown tier list: " + tierlistArg +
                    " (valid: mctiers, outertiers, pvptiers, subtiers, all)"));
                return;
            }
        }

        PlayerData a = e1.get();
        PlayerData b = e2.get();
        final TierService onlyFinal = only;
        String header = onlyFinal == null ? "Tier Compare" : "Tier Compare – " + onlyFinal.displayName;
        src.sendSuccess(() -> Component.literal("§6§l━━━━━━━━━ §f§l" + header + " §6§l━━━━━━━━━"), false);
        src.sendSuccess(() -> Component.literal(String.format(" §b§l%-12s §7vs §b§l%s", n1, n2)), false);
        src.sendSuccess(() -> Component.literal("§8─────────────────────────────────────"), false);
        int wins1 = 0, wins2 = 0, ties = 0;
        for (TierService svc : TierService.values()) {
            if (onlyFinal != null && svc != onlyFinal) continue;
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
                final String row = String.format(" §7%-3s§r §f%-9s §r%s §r %s§r %s§r",
                    svc.shortLabel, mode, c1, marker, c2);
                src.sendSuccess(() -> Component.literal(row), false);
            }
        }
        src.sendSuccess(() -> Component.literal("§8─────────────────────────────────────"), false);
        final int w1 = wins1, w2 = wins2, t = ties;
        src.sendSuccess(() -> Component.literal(String.format(" §7Wins: §a%s§7: §f%d  §7- §a%s§7: §f%d  §7(§e%d tie%s§7)",
            n1, w1, n2, w2, t, t == 1 ? "" : "s")), false);
    }

    private static void sendStatus(CommandSourceStack src) {
        TierConfig c = TierTaggerCore.config();
        StringBuilder svc = new StringBuilder();
        for (Map.Entry<String, Boolean> e : c.services.entrySet()) {
            svc.append("§e").append(e.getKey()).append("§7=")
                .append(e.getValue() ? "§aon" : "§coff").append("§r ");
        }
        src.sendSuccess(() -> Component.literal(
            "§a[TierTagger] §rv" + TierTaggerCore.MOD_VERSION + "\n" +
            " §7display§r: §e" + c.displayMode + "\n" +
            " §7left badge§r: §e" + c.leftService + "\n" +
            " §7right badge§r: §e" + c.rightService + "  §7(enabled: " + (c.rightBadgeEnabled ? "§aon" : "§coff") + "§7)\n" +
            " §7tab§r: " + (c.showInTab ? "§aon" : "§coff") +
                "  §7nametag§r: " + (c.showNametag ? "§aon" : "§coff") +
                "  §7coloured§r: " + (c.coloredBadges ? "§aon" : "§coff") + "\n" +
            " §7services§r: " + svc.toString() + "\n" +
            " §7format§r: §e" + c.badgeFormat + "  §7TTL§r: §e" + c.cacheTtlSeconds + "s\n" +
            "§7Type §f/tiertagger help §7for the full command list."), false);
    }

    private static void sendHelp(CommandSourceStack src) {
        src.sendSuccess(() -> Component.literal("§a===== §fTierTagger Commands §a====="), false);
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
        for (String[] r : rows) {
            src.sendSuccess(() -> Component.literal("§e" + r[0] + " §8— §7" + r[1]), false);
        }
        src.sendSuccess(() -> Component.literal("§7Services: §emctiers §8/ §eoutertiers §8/ §epvptiers §8/ §esubtiers"), false);
    }
}

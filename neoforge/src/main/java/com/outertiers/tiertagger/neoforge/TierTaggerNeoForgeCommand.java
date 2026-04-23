package com.outertiers.tiertagger.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.outertiers.tiertagger.common.TierCache;
import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.neoforge.screen.TierConfigScreen;
import com.outertiers.tiertagger.neoforge.screen.TierProfileScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.Optional;

public class TierTaggerNeoForgeCommand {

    private static final String MOD_VERSION = "1.2.0";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tiertagger")
            .then(Commands.literal("help").executes(c -> { sendHelp(c.getSource()); return 1; }))
            .then(Commands.literal("version").executes(c -> {
                c.getSource().sendSuccess(() -> Component.literal("§a[TierTagger] §rversion §e" + MOD_VERSION), false);
                return 1;
            }))
            .then(Commands.literal("status").executes(c -> { sendStatus(c.getSource()); return 1; }))
            .then(Commands.literal("reload").executes(c -> {
                TierConfig fresh = TierConfig.load();
                TierConfig cur = TierTaggerCore.config();
                cur.apiBase = fresh.apiBase;
                cur.gamemode = fresh.gamemode;
                cur.showInTab = fresh.showInTab;
                cur.showNametag = fresh.showNametag;
                cur.showPeak = fresh.showPeak;
                cur.fallthroughToHighest = fresh.fallthroughToHighest;
                cur.coloredBadges = fresh.coloredBadges;
                cur.badgeFormat = fresh.badgeFormat;
                cur.cacheTtlSeconds = fresh.cacheTtlSeconds;
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
                        c.getSource().sendSuccess(() -> Component.literal("§a[TierTagger] §rCache TTL set to §e" + s + "s§r."), false);
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
                        Optional<TierCache.Entry> opt = TierTaggerCore.cache().peek(name);
                        if (opt.isEmpty()) {
                            c.getSource().sendSuccess(() -> Component.literal("§7[TierTagger] §rFetching §e" + name + "§r…"), false);
                            return 1;
                        }
                        TierCache.Entry e = opt.get();
                        if (e.missing) {
                            c.getSource().sendSuccess(() -> Component.literal("§c[TierTagger] §rNo data for §e" + name), false);
                            return 1;
                        }
                        String tier = TierTaggerCore.chooseTier(e);
                        String peak = e.peakTier == null ? "—" : e.peakTier.toUpperCase();
                        String region = e.region == null ? "—" : e.region;
                        int n = e.tiers == null ? 0 : e.tiers.size();
                        c.getSource().sendSuccess(() -> Component.literal(
                            "§a[TierTagger] §e" + name + "§r — current: §6" + (tier == null ? "unranked" : tier) +
                            "§r, peak: §6" + peak + "§r, region: §b" + region + "§r, modes: §f" + n), false);
                        return 1;
                    })))
            .then(Commands.literal("compare")
                .then(Commands.argument("player1", StringArgumentType.word())
                    .then(Commands.argument("player2", StringArgumentType.word())
                        .executes(c -> {
                            String n1 = StringArgumentType.getString(c, "player1");
                            String n2 = StringArgumentType.getString(c, "player2");
                            sendCompare(c.getSource(), n1, n2);
                            return 1;
                        }))))
            .then(Commands.literal("clear")
                .then(Commands.argument("player", StringArgumentType.word())
                    .executes(c -> {
                        String name = StringArgumentType.getString(c, "player");
                        TierTaggerCore.cache().invalidatePlayer(name);
                        c.getSource().sendSuccess(() -> Component.literal("§a[TierTagger] §rCleared cache for §e" + name), false);
                        return 1;
                    })))
            .then(Commands.literal("mode")
                .then(Commands.argument("gamemode", StringArgumentType.word())
                    .suggests((ctx, builder) -> { for (String g : TierConfig.GAMEMODES) builder.suggest(g); return builder.buildFuture(); })
                    .executes(ctx -> {
                        String g = StringArgumentType.getString(ctx, "gamemode").toLowerCase();
                        if (!TierConfig.isValidGamemode(g)) {
                            ctx.getSource().sendFailure(Component.literal("Unknown gamemode: " + g));
                            return 0;
                        }
                        TierTaggerCore.config().gamemode = g;
                        TierTaggerCore.config().save();
                        TierTaggerCore.cache().invalidate();
                        ctx.getSource().sendSuccess(() -> Component.literal("§a[TierTagger] §rNow showing §e" + g + " §rtiers."), false);
                        return 1;
                    })))
            .then(Commands.literal("toggle")
                .executes(ctx -> {
                    TierTaggerCore.config().showInTab = !TierTaggerCore.config().showInTab;
                    TierTaggerCore.config().save();
                    boolean on = TierTaggerCore.config().showInTab;
                    ctx.getSource().sendSuccess(() -> Component.literal("§a[TierTagger] §rTab badges: " + (on ? "§aon" : "§cdisabled")), false);
                    return 1;
                }))
            .then(Commands.literal("nametag")
                .executes(ctx -> {
                    TierTaggerCore.config().showNametag = !TierTaggerCore.config().showNametag;
                    TierTaggerCore.config().save();
                    boolean on = TierTaggerCore.config().showNametag;
                    ctx.getSource().sendSuccess(() -> Component.literal("§a[TierTagger] §rNametag badges: " + (on ? "§aon" : "§cdisabled")), false);
                    return 1;
                }))
            .then(Commands.literal("fallthrough")
                .executes(ctx -> {
                    TierTaggerCore.config().fallthroughToHighest = !TierTaggerCore.config().fallthroughToHighest;
                    TierTaggerCore.config().save();
                    TierTaggerCore.cache().invalidate();
                    boolean on = TierTaggerCore.config().fallthroughToHighest;
                    ctx.getSource().sendSuccess(() -> Component.literal("§a[TierTagger] §rFall-through to highest: " + (on ? "§aon" : "§coff")), false);
                    return 1;
                }))
            .then(Commands.literal("peak")
                .executes(ctx -> {
                    TierTaggerCore.config().showPeak = !TierTaggerCore.config().showPeak;
                    TierTaggerCore.config().save();
                    TierTaggerCore.cache().invalidate();
                    boolean on = TierTaggerCore.config().showPeak;
                    ctx.getSource().sendSuccess(() -> Component.literal("§a[TierTagger] §rShow peak tier: " + (on ? "§aon" : "§coff")), false);
                    return 1;
                }))
            .then(Commands.literal("refresh")
                .executes(ctx -> {
                    TierTaggerCore.cache().invalidate();
                    ctx.getSource().sendSuccess(() -> Component.literal("§a[TierTagger] §rCache cleared."), false);
                    return 1;
                }))
            .then(Commands.literal("api")
                .then(Commands.argument("url", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        String url = StringArgumentType.getString(ctx, "url").trim();
                        TierTaggerCore.config().apiBase = url;
                        TierTaggerCore.config().save();
                        TierTaggerCore.cache().invalidate();
                        ctx.getSource().sendSuccess(() -> Component.literal("§a[TierTagger] §rAPI base: §e" + url), false);
                        return 1;
                    })))
            .then(Commands.literal("config").executes(ctx -> {
                Minecraft.getInstance().execute(() ->
                    Minecraft.getInstance().setScreen(new TierConfigScreen(null)));
                return 1;
            }))
            .then(Commands.literal("profile")
                .then(Commands.argument("player", StringArgumentType.word())
                    .executes(ctx -> {
                        String name = StringArgumentType.getString(ctx, "player");
                        Minecraft.getInstance().execute(() ->
                            Minecraft.getInstance().setScreen(new TierProfileScreen(null, name)));
                        return 1;
                    })))
            .executes(ctx -> { sendStatus(ctx.getSource()); return 1; })
        );
    }

    private static void sendCompare(CommandSourceStack src, String n1, String n2) {
        Optional<TierCache.Entry> e1 = TierTaggerCore.cache().peek(n1);
        Optional<TierCache.Entry> e2 = TierTaggerCore.cache().peek(n2);
        if (e1.isEmpty() || e2.isEmpty()) {
            src.sendSuccess(() -> Component.literal("§7[TierTagger] §rFetching tiers — try again in a moment…"), false);
            return;
        }
        TierCache.Entry a = e1.get();
        TierCache.Entry b = e2.get();
        if (a.missing && b.missing) {
            src.sendSuccess(() -> Component.literal("§c[TierTagger] §rNo data for §e" + n1 + " §ror §e" + n2), false);
            return;
        }
        src.sendSuccess(() -> Component.literal("§a===== §fCompare: §e" + n1 + " §7vs §e" + n2 + " §a====="), false);
        for (String mode : TierConfig.GAMEMODES) {
            if ("overall".equals(mode)) continue;
            String t1 = a.missing || a.tiers == null ? null : a.tiers.get(mode);
            String t2 = b.missing || b.tiers == null ? null : b.tiers.get(mode);
            int s1 = TierTaggerCore.score(t1);
            int s2 = TierTaggerCore.score(t2);
            String marker;
            if (t1 == null && t2 == null) marker = "§8=";
            else if (s1 > s2) marker = "§a<";
            else if (s2 > s1) marker = "§a>";
            else marker = "§e=";
            String c1 = t1 == null ? "§8unranked" : "§" + TierTaggerCore.colourCodeFor(t1) + "§l" + t1.toUpperCase();
            String c2 = t2 == null ? "§8unranked" : "§" + TierTaggerCore.colourCodeFor(t2) + "§l" + t2.toUpperCase();
            final String row = String.format("§7%-10s §r%s §r%s %s §r", mode, c1, marker, c2);
            src.sendSuccess(() -> Component.literal(row), false);
        }
        String top1 = TierTaggerCore.pickHighest(a);
        String top2 = TierTaggerCore.pickHighest(b);
        src.sendSuccess(() -> Component.literal("§7Highest: §f" + n1 + " §8→ §f" + (top1 == null ? "—" : top1) +
            "§r, §f" + n2 + " §8→ §f" + (top2 == null ? "—" : top2)), false);
    }

    private static void sendStatus(CommandSourceStack src) {
        TierConfig c = TierTaggerCore.config();
        src.sendSuccess(() -> Component.literal(
            "§a[TierTagger] §rv" + MOD_VERSION + "\n" +
            " §7gamemode§r: §e" + c.gamemode + "\n" +
            " §7tab badges§r: " + (c.showInTab ? "§aon" : "§coff") + "\n" +
            " §7nametag badges§r: " + (c.showNametag ? "§aon" : "§coff") + "\n" +
            " §7show peak§r: " + (c.showPeak ? "§aon" : "§coff") + "\n" +
            " §7fallthrough§r: " + (c.fallthroughToHighest ? "§aon" : "§coff") + "\n" +
            " §7coloured§r: " + (c.coloredBadges ? "§aon" : "§coff") + "\n" +
            " §7format§r: §e" + c.badgeFormat + "\n" +
            " §7cache TTL§r: §e" + c.cacheTtlSeconds + "s\n" +
            " §7api§r: §e" + c.apiBase + "\n" +
            "§7Type §f/tiertagger help §7for the full command list."), false);
    }

    private static void sendHelp(CommandSourceStack src) {
        src.sendSuccess(() -> Component.literal("§a===== §fTierTagger Commands §a====="), false);
        String[][] rows = {
            {"/tiertagger",                  "Show current settings"},
            {"/tiertagger help",             "Show this help"},
            {"/tiertagger version",          "Show mod version"},
            {"/tiertagger status",           "Show settings + cache TTL"},
            {"/tiertagger config",           "Open the GUI settings screen"},
            {"/tiertagger profile <player>", "Open a player's tier breakdown"},
            {"/tiertagger lookup <player>",  "Print a player's tiers in chat"},
            {"/tiertagger compare <a> <b>",  "Side-by-side tier comparison"},
            {"/tiertagger clear <player>",   "Forget a single player from cache"},
            {"/tiertagger mode <gamemode>",  "Switch active gamemode"},
            {"/tiertagger toggle",           "Toggle tab list badges"},
            {"/tiertagger nametag",          "Toggle badges above heads"},
            {"/tiertagger fallthrough",      "Toggle fall-through to highest tier"},
            {"/tiertagger peak",             "Toggle showing peak tier"},
            {"/tiertagger color",            "Toggle coloured badges"},
            {"/tiertagger format <style>",   "bracket | plain | short"},
            {"/tiertagger ttl <seconds>",    "Set cache TTL in seconds"},
            {"/tiertagger refresh",          "Clear the cache and re-fetch"},
            {"/tiertagger reload",           "Reload config from disk"},
            {"/tiertagger reset",            "Reset config to defaults"},
            {"/tiertagger api <url>",        "Override the API base URL"},
        };
        for (String[] r : rows) {
            src.sendSuccess(() -> Component.literal("§e" + r[0] + " §8— §7" + r[1]), false);
        }
    }
}

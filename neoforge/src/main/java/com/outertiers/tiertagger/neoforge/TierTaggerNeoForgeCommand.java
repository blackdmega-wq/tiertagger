package com.outertiers.tiertagger.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierTaggerCore;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class TierTaggerNeoForgeCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tiertagger")
            .then(Commands.literal("mode")
                .then(Commands.argument("gamemode", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        for (String g : TierConfig.GAMEMODES) builder.suggest(g);
                        return builder.buildFuture();
                    })
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
            .executes(ctx -> {
                TierConfig c = TierTaggerCore.config();
                ctx.getSource().sendSuccess(() -> Component.literal(
                    "§a[TierTagger] §rConfig:\n" +
                    " §7gamemode§r: §e" + c.gamemode + "\n" +
                    " §7tab badges§r: " + (c.showInTab ? "§aon" : "§coff") + "\n" +
                    " §7show peak§r: " + (c.showPeak ? "§aon" : "§coff") + "\n" +
                    " §7api§r: §e" + c.apiBase + "\n" +
                    "§7Subcommands: §fmode <name>, toggle, peak, refresh, api <url>"), false);
                return 1;
            })
        );
    }
}

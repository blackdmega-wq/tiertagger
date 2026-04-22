package com.outertiers.tiertagger.fabric;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierTaggerCore;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.text.Text;

public class TierTaggerFabricCommand {
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("tiertagger")
                .then(ClientCommandManager.literal("mode")
                    .then(ClientCommandManager.argument("gamemode", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            for (String g : TierConfig.GAMEMODES) builder.suggest(g);
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            String g = StringArgumentType.getString(ctx, "gamemode").toLowerCase();
                            if (!TierConfig.isValidGamemode(g)) {
                                ctx.getSource().sendError(Text.literal("Unknown gamemode: " + g));
                                return 0;
                            }
                            TierTaggerCore.config().gamemode = g;
                            TierTaggerCore.config().save();
                            TierTaggerCore.cache().invalidate();
                            ctx.getSource().sendFeedback(Text.literal("§a[TierTagger] §rNow showing §e" + g + " §rtiers."));
                            return 1;
                        })))
                .then(ClientCommandManager.literal("toggle")
                    .executes(ctx -> {
                        TierTaggerCore.config().showInTab = !TierTaggerCore.config().showInTab;
                        TierTaggerCore.config().save();
                        ctx.getSource().sendFeedback(Text.literal("§a[TierTagger] §rTab badges: " +
                            (TierTaggerCore.config().showInTab ? "§aon" : "§cdisabled")));
                        return 1;
                    }))
                .then(ClientCommandManager.literal("peak")
                    .executes(ctx -> {
                        TierTaggerCore.config().showPeak = !TierTaggerCore.config().showPeak;
                        TierTaggerCore.config().save();
                        TierTaggerCore.cache().invalidate();
                        ctx.getSource().sendFeedback(Text.literal("§a[TierTagger] §rShow peak tier: " +
                            (TierTaggerCore.config().showPeak ? "§aon" : "§coff")));
                        return 1;
                    }))
                .then(ClientCommandManager.literal("refresh")
                    .executes(ctx -> {
                        TierTaggerCore.cache().invalidate();
                        ctx.getSource().sendFeedback(Text.literal("§a[TierTagger] §rCache cleared."));
                        return 1;
                    }))
                .then(ClientCommandManager.literal("api")
                    .then(ClientCommandManager.argument("url", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String url = StringArgumentType.getString(ctx, "url").trim();
                            TierTaggerCore.config().apiBase = url;
                            TierTaggerCore.config().save();
                            TierTaggerCore.cache().invalidate();
                            ctx.getSource().sendFeedback(Text.literal("§a[TierTagger] §rAPI base: §e" + url));
                            return 1;
                        })))
                .executes(ctx -> {
                    TierConfig c = TierTaggerCore.config();
                    ctx.getSource().sendFeedback(Text.literal(
                        "§a[TierTagger] §rConfig:\n" +
                        " §7gamemode§r: §e" + c.gamemode + "\n" +
                        " §7tab badges§r: " + (c.showInTab ? "§aon" : "§coff") + "\n" +
                        " §7show peak§r: " + (c.showPeak ? "§aon" : "§coff") + "\n" +
                        " §7api§r: §e" + c.apiBase + "\n" +
                        "§7Subcommands: §fmode <name>, toggle, peak, refresh, api <url>"));
                    return 1;
                })
            );
        });
    }
}

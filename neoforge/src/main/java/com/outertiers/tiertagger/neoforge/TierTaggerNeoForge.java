package com.outertiers.tiertagger.neoforge;

import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.neoforge.screen.TierConfigScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = TierTaggerCore.MOD_ID, dist = Dist.CLIENT)
public class TierTaggerNeoForge {
    public TierTaggerNeoForge(IEventBus modBus, ModContainer container) {
        TierConfig.setConfigDir(FMLPaths.CONFIGDIR.get());
        TierTaggerCore.init();
        PendingScreen.register();
        TierKeybinds.register(modBus);

        // Register the config screen so it appears under
        // Minecraft Options -> Mods -> TierTagger -> Configure.
        try {
            container.registerExtensionPoint(IConfigScreenFactory.class,
                (mc, parent) -> new TierConfigScreen(parent));
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] could not register IConfigScreenFactory: {}", t.toString());
        }

        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);

        try {
            UpdateNotifier.register();
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] update-notifier register failed: {}", t.toString());
        }
    }

    private void onRegisterCommands(RegisterClientCommandsEvent event) {
        TierTaggerNeoForgeCommand.register(event.getDispatcher());
    }
}

package com.outertiers.tiertagger.neoforge;

import com.outertiers.tiertagger.common.TierTaggerCore;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = TierTaggerCore.MOD_ID, dist = Dist.CLIENT)
public class TierTaggerNeoForge {
    public TierTaggerNeoForge(IEventBus modBus) {
        TierTaggerCore.init();
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }

    private void onRegisterCommands(RegisterClientCommandsEvent event) {
        TierTaggerNeoForgeCommand.register(event.getDispatcher());
    }
}

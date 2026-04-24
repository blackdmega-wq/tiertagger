package com.outertiers.tiertagger.fabric.modmenu;

import com.outertiers.tiertagger.fabric.screen.TierConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Registers TierTagger's config screen with Mod Menu, so users can open it
 * from <em>Minecraft Options &rarr; Mods &rarr; TierTagger &rarr; Configure</em>
 * without having to type a command.
 *
 * <p>This class is only resolved at runtime if Mod Menu is installed, which is
 * why we list it under the dedicated {@code modmenu} entrypoint instead of
 * {@code client}.</p>
 */
public class TierModMenuApi implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new TierConfigScreen(parent);
    }
}

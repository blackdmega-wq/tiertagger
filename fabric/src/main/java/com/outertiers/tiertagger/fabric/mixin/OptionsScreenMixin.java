package com.outertiers.tiertagger.fabric.mixin;

import com.outertiers.tiertagger.fabric.screen.TierConfigScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects a "TierTagger" button into Minecraft's Options screen so the user
 * can open the mod settings directly from Options without needing Mod Menu.
 *
 * The button is placed in the bottom-left corner which is consistently empty
 * space across all Minecraft GUI scale settings.
 */
@Mixin(OptionsScreen.class)
public class OptionsScreenMixin {

    @Inject(method = "init", at = @At("TAIL"))
    private void tiertagger$addButton(CallbackInfo ci) {
        OptionsScreen self = (OptionsScreen)(Object)this;
        self.addDrawableChild(
            ButtonWidget.builder(
                Text.literal("§e[TierTagger]"),
                btn -> {
                    MinecraftClient mc = MinecraftClient.getInstance();
                    if (mc != null) mc.setScreen(new TierConfigScreen(self));
                })
            .dimensions(4, self.height - 24, 100, 20)
            .build()
        );
    }
}

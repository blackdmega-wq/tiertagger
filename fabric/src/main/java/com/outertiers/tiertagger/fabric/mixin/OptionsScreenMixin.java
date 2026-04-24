package com.outertiers.tiertagger.fabric.mixin;

import com.outertiers.tiertagger.fabric.screen.TierConfigScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
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
 *
 * NOTE: This class extends Screen at compile time so that the `protected`
 * method addDrawableChild is reachable. At runtime Mixin merges the
 * inject method into OptionsScreen itself, so the `extends Screen`
 * declaration is purely a compile-time accessibility shim and is never
 * actually used as a base class.
 */
@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin extends Screen {

    protected OptionsScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void tiertagger$addButton(CallbackInfo ci) {
        this.addDrawableChild(
            ButtonWidget.builder(
                Text.literal("\u00a7e[TierTagger]"),
                btn -> {
                    MinecraftClient mc = MinecraftClient.getInstance();
                    if (mc != null) mc.setScreen(new TierConfigScreen((Screen)(Object)this));
                })
            .dimensions(4, this.height - 24, 100, 20)
            .build()
        );
    }
}

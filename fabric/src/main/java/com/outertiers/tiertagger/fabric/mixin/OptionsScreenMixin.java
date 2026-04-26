package com.outertiers.tiertagger.fabric.mixin;

import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.fabric.screen.TierConfigScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Injects a "TierTagger" button into Minecraft's Options screen so the user
 * can open the mod settings directly from Options without needing Mod Menu.
 *
 * Placement: directly UNDER the vanilla "Credits & Attribution" button.
 * We scan the live widget tree at the end of {@code init()} to find the
 * Credits button by its localised text, compute the layout cell it
 * occupies, and:
 *   1. shift any widget visually below the Credits row (typically "Done")
 *      DOWN by one row so the new button has a clear slot,
 *   2. place the TierTagger button one row BELOW Credits with the same
 *      width / height — making it sit visually directly under the
 *      Credits & Attribution button, as the user requested.
 *
 * If we can't find the Credits anchor button (e.g. a future MC rework),
 * we silently fall back to the legacy bottom-left position so the mod
 * keeps working everywhere.
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

    /**
     * require = 0: if {@code init()} is ever renamed/refactored on a future MC
     * version, the mixin silently no-ops (user just won't see the in-options
     * shortcut button) instead of preventing the entire mod — and therefore
     * the entire client — from loading.
     */
    @Inject(method = "init", at = @At("TAIL"), require = 0)
    private void tiertagger$addButton(CallbackInfo ci) {
        try {
            ButtonWidget credits = findButtonByKey("credits_and_attribution", "credits");

            if (credits != null) {
                int cx = credits.getX();
                int cy = credits.getY();
                int cw = credits.getWidth();
                int ch = credits.getHeight();
                int rowH = ch + 4;

                // Push anything visually BELOW the Credits row (typically
                // "Done") down by one row so the new button can occupy
                // the slot directly under Credits — that way TierTagger
                // sits one row below Credits & Attribution, exactly where
                // the user asked for it.
                int rowYThreshold = credits.getY() + 1;
                for (Element el : new ArrayList<>(this.children())) {
                    if (!(el instanceof ClickableWidget cw2)) continue;
                    if (cw2 == credits) continue;
                    if (cw2.getY() >= rowYThreshold) {
                        cw2.setY(cw2.getY() + rowH);
                    }
                }

                this.addDrawableChild(
                    ButtonWidget.builder(
                        Text.literal("\u00a7e[TierTagger]"),
                        btn -> {
                            MinecraftClient mc = MinecraftClient.getInstance();
                            if (mc != null) mc.setScreen(new TierConfigScreen((Screen)(Object)this));
                        })
                    .dimensions(cx, cy + rowH, cw, ch)
                    .build()
                );
                return;
            }
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] could not insert Options-screen button under Credits, falling back: {}", t.toString());
        }

        // Fallback: original bottom-left position so older or non-vanilla
        // Options screens still get the shortcut.
        try {
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
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] could not add Options-screen button: {}", t.toString());
        }
    }

    /**
     * Locate a vanilla {@link ButtonWidget} on the Options screen by matching
     * its visible text against any of the supplied substrings (case-insensitive).
     * Returns {@code null} if no candidate matches — safer than blowing up the
     * Options screen on locales / mods we didn't anticipate.
     */
    private ButtonWidget findButtonByKey(String... needles) {
        if (needles == null || needles.length == 0) return null;
        List<String> needleList = new ArrayList<>();
        for (String n : needles) if (n != null && !n.isBlank()) needleList.add(n.toLowerCase(Locale.ROOT));
        for (Element el : this.children()) {
            if (!(el instanceof ButtonWidget btn)) continue;
            String label;
            try {
                label = btn.getMessage().getString();
            } catch (Throwable t) { continue; }
            if (label == null || label.isBlank()) continue;
            String lower = label.toLowerCase(Locale.ROOT);
            for (String needle : needleList) {
                if (lower.contains(needle)) return btn;
            }
        }
        return null;
    }
}

package com.outertiers.tiertagger.fabric;

import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.fabric.screen.TierConfigScreen;
import com.outertiers.tiertagger.fabric.screen.TierProfileScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

/**
 * Registers TierTagger's client-side key bindings. The user can rebind any of
 * them under <em>Options &rarr; Controls &rarr; Key Binds &rarr; TierTagger</em>.
 *
 * <ul>
 *   <li>{@code key.tiertagger.config} &mdash; opens the config screen
 *       (default {@code K}).</li>
 *   <li>{@code key.tiertagger.profile} &mdash; opens the profile screen for
 *       the player you are currently looking at, up to 64 blocks away
 *       (default {@code J}).</li>
 * </ul>
 *
 * <p>Set any binding to <em>Not bound</em> in the controls menu to disable
 * it. The pick range for the profile binding is intentionally wider than the
 * vanilla interaction reach so it works at typical PvP distances.</p>
 */
public final class TierKeybinds {
    private static final double PROFILE_RANGE = 64.0;

    private static volatile boolean registered = false;
    private static KeyBinding openConfig;
    private static KeyBinding openProfile;

    private TierKeybinds() {}

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        try {
            openConfig = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                    "key.tiertagger.config",
                    InputUtil.Type.KEYSYM,
                    GLFW.GLFW_KEY_K,
                    "key.categories.tiertagger"
            ));
            openProfile = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                    "key.tiertagger.profile",
                    InputUtil.Type.KEYSYM,
                    GLFW.GLFW_KEY_J,
                    "key.categories.tiertagger"
            ));
            ClientTickEvents.END_CLIENT_TICK.register(TierKeybinds::onTick);
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] could not register key bindings: {}", t.toString());
        }
    }

    private static void onTick(MinecraftClient client) {
        if (client == null) return;
        // Drain wasPressed() so a held key triggers the action exactly once
        // per press.
        while (openConfig != null && openConfig.wasPressed()) {
            if (client.currentScreen != null) continue;
            PendingScreen.open(new TierConfigScreen(null));
        }
        while (openProfile != null && openProfile.wasPressed()) {
            if (client.currentScreen != null) continue;
            PlayerEntity target = findTargetedPlayer(client);
            if (target == null) continue;
            String name = target.getGameProfile() != null
                    ? target.getGameProfile().getName()
                    : target.getName().getString();
            if (name == null || name.isEmpty()) continue;
            PendingScreen.open(new TierProfileScreen(null, name));
        }
    }

    /**
     * Returns the {@link PlayerEntity} the local camera is currently pointed
     * at within {@link #PROFILE_RANGE} blocks, or {@code null} if no player
     * is in the line of sight.
     */
    private static PlayerEntity findTargetedPlayer(MinecraftClient mc) {
        try {
            if (mc.player == null || mc.world == null) return null;
            Entity camera = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
            Vec3d eye  = camera.getCameraPosVec(1.0f);
            Vec3d look = camera.getRotationVec(1.0f);
            Vec3d end  = eye.add(look.multiply(PROFILE_RANGE));
            Box box = camera.getBoundingBox()
                    .stretch(look.multiply(PROFILE_RANGE))
                    .expand(1.0, 1.0, 1.0);
            EntityHitResult hit = ProjectileUtil.raycast(
                    camera, eye, end, box,
                    e -> e instanceof PlayerEntity && e != camera && !e.isSpectator(),
                    PROFILE_RANGE * PROFILE_RANGE
            );
            if (hit != null && hit.getEntity() instanceof PlayerEntity p) return p;
            return null;
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] target raycast failed: {}", t.toString());
            return null;
        }
    }
}

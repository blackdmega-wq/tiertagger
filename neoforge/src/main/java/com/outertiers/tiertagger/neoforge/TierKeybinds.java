package com.outertiers.tiertagger.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.neoforge.screen.TierConfigScreen;
import com.outertiers.tiertagger.neoforge.screen.TierProfileScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/**
 * NeoForge twin of the Fabric {@code TierKeybinds} helper. Registers two
 * client-side key bindings under <em>Options &rarr; Controls &rarr; Key Binds
 * &rarr; TierTagger</em>:
 *
 * <ul>
 *   <li>{@code key.tiertagger.config} (default {@code K}) — opens the config
 *       screen.</li>
 *   <li>{@code key.tiertagger.profile} (default {@code J}) — opens the
 *       profile screen for the player you are currently looking at, up to
 *       64 blocks away.</li>
 * </ul>
 */
public final class TierKeybinds {
    private static final double PROFILE_RANGE = 64.0;

    private static volatile boolean registered = false;

    private static final KeyMapping OPEN_CONFIG = new KeyMapping(
            "key.tiertagger.config",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "key.categories.tiertagger"
    );

    private static final KeyMapping OPEN_PROFILE = new KeyMapping(
            "key.tiertagger.profile",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_J,
            "key.categories.tiertagger"
    );

    private TierKeybinds() {}

    public static synchronized void register(IEventBus modBus) {
        if (registered) return;
        registered = true;
        try {
            modBus.addListener((RegisterKeyMappingsEvent evt) -> {
                evt.register(OPEN_CONFIG);
                evt.register(OPEN_PROFILE);
            });
            NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post evt) -> onTick());
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] could not register key bindings: {}", t.toString());
        }
    }

    private static void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        while (OPEN_CONFIG.consumeClick()) {
            if (mc.screen != null) continue;
            PendingScreen.open(new TierConfigScreen(null));
        }
        while (OPEN_PROFILE.consumeClick()) {
            if (mc.screen != null) continue;
            Player target = findTargetedPlayer(mc);
            if (target == null) continue;
            String name = target.getGameProfile() != null
                    ? target.getGameProfile().getName()
                    : target.getName().getString();
            if (name == null || name.isEmpty()) continue;
            PendingScreen.open(new TierProfileScreen(null, name));
        }
    }

    /**
     * Returns the {@link Player} the local camera is currently pointed at
     * within {@link #PROFILE_RANGE} blocks, or {@code null} if no player is
     * in the line of sight.
     */
    private static Player findTargetedPlayer(Minecraft mc) {
        try {
            if (mc.player == null || mc.level == null) return null;
            Entity camera = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
            Vec3 eye  = camera.getEyePosition(1.0f);
            Vec3 look = camera.getViewVector(1.0f);
            Vec3 end  = eye.add(look.scale(PROFILE_RANGE));
            AABB box = camera.getBoundingBox()
                    .expandTowards(look.scale(PROFILE_RANGE))
                    .inflate(1.0, 1.0, 1.0);
            EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                    camera, eye, end, box,
                    e -> e instanceof Player && e != camera && !e.isSpectator(),
                    PROFILE_RANGE * PROFILE_RANGE
            );
            if (hit != null && hit.getEntity() instanceof Player p) return p;
            return null;
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] target raycast failed: {}", t.toString());
            return null;
        }
    }
}

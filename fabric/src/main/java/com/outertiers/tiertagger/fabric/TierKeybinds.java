package com.outertiers.tiertagger.fabric;

import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierService;
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
import net.minecraft.text.Text;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
 *   <li>{@code key.tiertagger.cyclemode} &mdash; cycles the active right-side
 *       gamemode through {@code highest} and every mode the right service
 *       exposes, mirroring the in-config "Cycle Right Mode" button so the
 *       same action is reachable from the keyboard (default {@code I}).</li>
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
    private static KeyBinding cycleRightMode;

    private TierKeybinds() {}

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        try {
            openConfig = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                    "key.tiertagger.config",
                    InputUtil.Type.KEYSYM,
                    GLFW.GLFW_KEY_K,
                    KeyBinding.Category.MISC
            ));
            openProfile = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                    "key.tiertagger.profile",
                    InputUtil.Type.KEYSYM,
                    GLFW.GLFW_KEY_J,
                    KeyBinding.Category.MISC
            ));
            // 'I' cycles the right-side gamemode (mirrors the in-config
            // "Cycle Right Mode" button). Lives in the same MISC category as
            // the other TierTagger binds so users find them grouped together
            // in vanilla Controls → Key Binds.
            cycleRightMode = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                    "key.tiertagger.cyclemode",
                    InputUtil.Type.KEYSYM,
                    GLFW.GLFW_KEY_I,
                    KeyBinding.Category.MISC
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
                    ? com.outertiers.tiertagger.fabric.compat.Profiles.name(target.getGameProfile())
                    : target.getName().getString();
            if (name == null || name.isEmpty()) continue;
            PendingScreen.open(new TierProfileScreen(null, name));
        }
        // 'I' — cycle right-side gamemode, regardless of whether a screen is
        // open (we want the binding usable from in-game AND from inventory).
        while (cycleRightMode != null && cycleRightMode.wasPressed()) {
            cycleRightModeAction(client);
        }
    }

    /**
     * Cycle TierConfig#rightMode through {@code "highest"} + every mode the
     * currently-selected right service exposes. This is the keyboard twin of
     * the "Cycle Right Mode" icon button on the Tiers Config tab so users
     * can flip modes without opening the menu mid-fight.
     */
    private static void cycleRightModeAction(MinecraftClient client) {
        try {
            TierConfig cfg = TierTaggerCore.config();
            if (cfg == null) return;
            TierService rightSvc = cfg.rightServiceEnum();
            if (rightSvc == null) return;
            List<String> modes = new ArrayList<>();
            modes.add("highest");
            for (String m : rightSvc.modes) if (!modes.contains(m)) modes.add(m);
            String cur = cfg.rightMode == null ? "highest" : cfg.rightMode.toLowerCase(Locale.ROOT);
            int idx = modes.indexOf(cur);
            cfg.rightMode = modes.get((idx < 0 ? 0 : (idx + 1) % modes.size()));
            cfg.save();
            // Brief actionbar feedback so users see the new mode right away.
            try {
                if (client != null && client.player != null) {
                    String label = "highest".equalsIgnoreCase(cfg.rightMode)
                            ? "Highest"
                            : Character.toUpperCase(cfg.rightMode.charAt(0)) + cfg.rightMode.substring(1);
                    client.player.sendMessage(
                            Text.literal("\u00a7e[TierTagger] \u00a7rRight mode: \u00a7a" + label
                                    + " \u00a77(" + rightSvc.shortLabel + ")"),
                            true);
                }
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] cycle-right-mode keybind failed: {}", t.toString());
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

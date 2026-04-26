package com.outertiers.tiertagger.fabric;

import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierService;
import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.fabric.screen.TierConfigScreen;
import com.outertiers.tiertagger.fabric.screen.TierProfileScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
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
    private static KeyMapping openConfig;
    private static KeyMapping openProfile;
    private static KeyMapping cycleRightMode;

    private TierKeybinds() {}

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        try {
            openConfig = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                    "key.tiertagger.config",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_K,
                    KeyMapping.Category.MISC
            ));
            openProfile = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                    "key.tiertagger.profile",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_J,
                    KeyMapping.Category.MISC
            ));
            // 'I' cycles the right-side gamemode (mirrors the in-config
            // "Cycle Right Mode" button). Lives in the same MISC category as
            // the other TierTagger binds so users find them grouped together
            // in vanilla Controls → Key Binds.
            cycleRightMode = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                    "key.tiertagger.cyclemode",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_I,
                    KeyMapping.Category.MISC
            ));
            ClientTickEvents.END_CLIENT_TICK.register(TierKeybinds::onTick);
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] could not register key bindings: {}", t.toString());
        }
    }

    private static void onTick(Minecraft client) {
        if (client == null) return;
        // Drain wasPressed() so a held key triggers the action exactly once
        // per press.
        while (openConfig != null && openConfig.wasPressed()) {
            if (client.currentScreen != null) continue;
            PendingScreen.open(new TierConfigScreen(null));
        }
        while (openProfile != null && openProfile.wasPressed()) {
            if (client.currentScreen != null) continue;
            Player target = findTargetedPlayer(client);
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
    private static void cycleRightModeAction(Minecraft client) {
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
                            Component.literal("\u00a7e[TierTagger] \u00a7rRight mode: \u00a7a" + label
                                    + " \u00a77(" + rightSvc.shortLabel + ")"),
                            true);
                }
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] cycle-right-mode keybind failed: {}", t.toString());
        }
    }

    // ── In-config keybind picker support ───────────────────────────────────

    /** GLFW key codes offered in the in-config Cycle Mode Key picker. */
    private static final int[] PICKER_KEYS = {
        GLFW.GLFW_KEY_I, GLFW.GLFW_KEY_K, GLFW.GLFW_KEY_O, GLFW.GLFW_KEY_T,
        GLFW.GLFW_KEY_U, GLFW.GLFW_KEY_Y, GLFW.GLFW_KEY_H, GLFW.GLFW_KEY_N,
        GLFW.GLFW_KEY_M, GLFW.GLFW_KEY_B, GLFW.GLFW_KEY_V, GLFW.GLFW_KEY_P,
        GLFW.GLFW_KEY_L, GLFW.GLFW_KEY_R, GLFW.GLFW_KEY_G, GLFW.GLFW_KEY_Z,
        GLFW.GLFW_KEY_X
    };

    /**
     * Returns the GLFW key currently bound to the cycle-mode keybind. Falls
     * back to {@code GLFW_KEY_I} when the keybind isn't initialised yet
     * (e.g. when called before {@link #register()} on early-init paths).
     */
    public static int getCycleKeyCode() {
        try {
            if (cycleRightMode == null) return GLFW.GLFW_KEY_I;
            String tk = cycleRightMode.getBoundKeyTranslationKey();
            if (tk == null || tk.isEmpty()) return GLFW.GLFW_KEY_I;
            InputConstants.Key k = InputConstants.fromTranslationKey(tk);
            if (k == null) return GLFW.GLFW_KEY_I;
            int code = k.getCode();
            return code <= 0 ? GLFW.GLFW_KEY_I : code;
        } catch (Throwable t) {
            return GLFW.GLFW_KEY_I;
        }
    }

    /**
     * Re-binds the cycle-mode keybind to the given GLFW key code and
     * persists the change to {@code options.txt} so it survives a restart.
     * Silently ignored when {@link #register()} hasn't run yet.
     */
    public static void setCycleKeyCode(int glfwKey) {
        try {
            if (cycleRightMode == null) return;
            cycleRightMode.setBoundKey(InputConstants.Type.KEYSYM.createFromCode(glfwKey));
            KeyMapping.updateKeysByCode();
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.options != null) {
                try { mc.options.write(); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] could not rebind cycle key: {}", t.toString());
        }
    }

    /**
     * Returns the picker's value list. Always includes the user's current
     * key (even if it isn't in the default {@link #PICKER_KEYS} table) so
     * the cycling button can display whatever they had before.
     */
    public static List<Integer> commonKeyCodes(int currentCode) {
        List<Integer> out = new ArrayList<>(PICKER_KEYS.length + 1);
        for (int k : PICKER_KEYS) out.add(k);
        if (!out.contains(currentCode) && currentCode > 0) out.add(currentCode);
        return out;
    }

    /** Pretty label for a GLFW key code, used by the cycling button. */
    public static String keyLabel(int glfwKey) {
        try {
            String s = InputConstants.Type.KEYSYM.createFromCode(glfwKey).getLocalizedText().getString();
            if (s != null && !s.isEmpty()) return s.toUpperCase(Locale.ROOT);
        } catch (Throwable ignored) {}
        return "KEY " + glfwKey;
    }

    /**
     * Returns the {@link Player} the local camera is currently pointed
     * at within {@link #PROFILE_RANGE} blocks, or {@code null} if no player
     * is in the line of sight.
     */
    private static Player findTargetedPlayer(Minecraft mc) {
        try {
            if (mc.player == null || mc.world == null) return null;
            Entity camera = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
            Vec3 eye  = camera.getCameraPosVec(1.0f);
            Vec3 look = camera.getRotationVec(1.0f);
            Vec3 end  = eye.add(look.multiply(PROFILE_RANGE));
            AABB box = camera.getBoundingBox()
                    .stretch(look.multiply(PROFILE_RANGE))
                    .expand(1.0, 1.0, 1.0);
            EntityHitResult hit = ProjectileUtil.raycast(
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

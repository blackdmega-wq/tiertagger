package com.outertiers.tiertagger.fabric;

import com.outertiers.tiertagger.common.PlayerData;
import com.outertiers.tiertagger.common.TierConfig;
import com.outertiers.tiertagger.common.TierTaggerCore;
import com.outertiers.tiertagger.fabric.compat.Profiles;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Wraps incoming chat messages so the sender's tier badge shows up in
 * front of the message. v1.21.11.39 is the first version where the
 * "Show tiers in chat" toggle in the config screen actually does
 * anything — previously the toggle was wired to the config field but
 * nothing on the receive path consumed the value.
 *
 * <p>Implementation strategy: read the plain string of every incoming
 * GAME / CHAT message, find the first online player whose name appears
 * as a whole-word substring, fetch (or queue) their tier data, and —
 * when data is ready — return {@code badge + " " + original}. The
 * original {@link Text} is appended unchanged so server-side styling
 * (name colours, click events, etc.) is preserved.</p>
 *
 * <p>Failures are swallowed: if anything throws, the original message
 * is returned untouched. The toggle {@code TierConfig.disableInChat}
 * acts as a hard kill switch.</p>
 */
public final class TierChatDecorator {
    private TierChatDecorator() {}

    /**
     * Wires the GAME receive path into our decorator. Modern servers (and
     * vanilla 1.20+) route most player chat through the GAME message
     * channel — including signed player chat after the 1.19.3 system-chat
     * unification — so a single registration covers the common cases
     * without depending on the {@code MODIFY_CHAT} event (which is not
     * present in every fabric-api build pinned across our MC matrix).
     */
    public static void register() {
        try {
            ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
                if (overlay) return message;          // never touch action-bar
                return decorate(message);
            });
        } catch (Throwable t) {
            TierTaggerCore.LOGGER.warn("[TierTagger] chat MODIFY_GAME register failed: {}", t.toString());
        }
    }

    /**
     * Pure decoration step. Returns the original message unchanged when
     * the user has disabled chat tiers, when no online player is mentioned,
     * or when the mentioned player has no cached tier data yet (in which
     * case a fetch is kicked off so the NEXT message from the same
     * player will be decorated).
     */
    private static Component decorate(Component message) {
        try {
            TierConfig cfg = TierTaggerCore.config();
            if (cfg == null || cfg.disableInChat) return message;
            String plain = message.getString();
            if (plain == null || plain.isEmpty()) return message;

            String matched = findOnlinePlayer(plain);
            if (matched == null) return message;

            // peekData() always returns a PlayerData — empty if nothing has
            // been fetched yet — and quietly kicks off the background fetch
            // for any service whose entry is missing or stale. We rely on
            // wrapNametag() returning null when no usable tier data exists,
            // which is the correct "not ready" signal here.
            Optional<PlayerData> opt = TierTaggerCore.cache().peekData(matched);
            if (opt.isEmpty()) return message;
            MutableComponent wrapped = BadgeRenderer.wrapNametag(cfg, opt.get(), Component.literal(""));
            if (wrapped == null) return message;
            // wrapNametag returns "<badges><original>" — we passed an empty
            // original, so this is just the badge cluster. Prepend it to
            // the real message with a single space separator.
            return Component.empty().append(wrapped).append(Component.literal(" ")).append(message);
        } catch (Throwable t) {
            return message;
        }
    }

    /**
     * Finds the first online player whose username appears as a complete
     * word inside {@code plain}. Returns the original-cased username (as
     * reported by the server's tab list) so the cache lookup hits.
     */
    private static String findOnlinePlayer(String plain) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getNetworkHandler() == null) return null;
            Collection<PlayerInfo> entries = mc.getNetworkHandler().getPlayerList();
            if (entries == null || entries.isEmpty()) return null;
            String lowerPlain = plain.toLowerCase(Locale.ROOT);
            // Sort by length descending so "Notch_Pro" matches before "Notch".
            String best = null;
            int bestLen = 0;
            Set<String> seen = new HashSet<>();
            for (PlayerInfo e : entries) {
                String name;
                try { name = Profiles.name(e.getProfile()); }
                catch (Throwable ignored) { continue; }
                if (name == null || name.isEmpty() || name.length() < 3) continue;
                if (!seen.add(name.toLowerCase(Locale.ROOT))) continue;
                if (name.length() <= bestLen) continue;
                if (containsWord(lowerPlain, name.toLowerCase(Locale.ROOT))) {
                    best = name;
                    bestLen = name.length();
                }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Returns true if {@code needle} appears in {@code haystack} bounded
     * by non-identifier characters (letters / digits / underscore). Avoids
     * spurious matches like "Steve" inside "Steven" or
     * "&lt;NotchBot&gt;" inside "MyNotchBot".
     */
    private static boolean containsWord(String haystack, String needle) {
        int from = 0;
        while (from <= haystack.length() - needle.length()) {
            int idx = haystack.indexOf(needle, from);
            if (idx < 0) return false;
            char before = idx == 0 ? ' ' : haystack.charAt(idx - 1);
            int endIdx = idx + needle.length();
            char after  = endIdx >= haystack.length() ? ' ' : haystack.charAt(endIdx);
            if (!isIdentifierChar(before) && !isIdentifierChar(after)) return true;
            from = idx + 1;
        }
        return false;
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}

package com.liu.liuchat.hook;

import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.core.font.EmojiUseCase;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;

/** Resolves CraftEngine's configured CHAT content (including its image and hover) at the source. */
public final class CraftEngineEmojiHook {
    private CraftEngineEmojiHook() { }

    public static Map<String, String> resolve(Player sender, String message) {
        if (!Bukkit.getPluginManager().isPluginEnabled("CraftEngine")) return Map.of();
        try {
            var engine = BukkitCraftEngine.instance();
            if (engine == null || !engine.isFullyLoaded()) return Map.of();
            var font = engine.fontManager();
            var player = BukkitAdaptor.adapt(sender);
            Map<String, String> results = new LinkedHashMap<>();
            for (var emoji : font.emojis().values()) {
                for (String keyword : emoji.keywords()) {
                    if (keyword.isEmpty() || !message.contains(keyword) || results.containsKey(keyword)) continue;
                    var parsed = font.replaceMiniMessageEmoji(keyword, player, EmojiUseCase.CHAT);
                    if (parsed.replaced() && parsed.text().length() <= 2048) results.put(keyword, parsed.text());
                    if (results.size() >= 16) return results;
                }
            }
            return results;
        } catch (LinkageError | RuntimeException ex) {
            Bukkit.getLogger().warning("LiuChat: CraftEngine 表情解析失败: " + ex);
            return Map.of();
        }
    }
}

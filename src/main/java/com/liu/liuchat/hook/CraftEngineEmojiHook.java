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
                    if (keyword.isEmpty()) continue;
                    var parsed = font.replaceComponentEmoji(
                            net.momirealms.craftengine.libraries.adventure.text.Component.text(keyword),
                            player, EmojiUseCase.CHAT);
                    if (!parsed.changed()) continue;
                    String glyph = net.momirealms.craftengine.core.util.AdventureHelper.plainTextContent(parsed.newText());
                    if (!message.contains(keyword) && (glyph.isEmpty() || !message.contains(glyph))) continue;
                    String json = net.momirealms.craftengine.core.util.AdventureHelper.componentToJson(parsed.newText());
                    String encoded = "ce-json:" + json;
                    if (encoded.length() > 6000) continue;
                    addMatches(results, message, keyword, glyph, encoded);
                    if (results.size() >= 16) return results;
                }
            }
            return results;
        } catch (LinkageError | RuntimeException ex) {
            Bukkit.getLogger().warning("LiuChat: CraftEngine 表情解析失败: " + ex);
            return Map.of();
        }
    }

    public static void addMatches(Map<String, String> results, String message, String keyword, String glyph, String encoded) {
        if (message.contains(keyword) && results.size() < 16) results.putIfAbsent(keyword, encoded);
        if (!glyph.isEmpty() && !glyph.equals(keyword) && message.contains(glyph) && results.size() < 16)
            results.putIfAbsent(glyph, encoded);
    }
}

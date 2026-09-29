package com.liu.liuchat.hook;

import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.core.font.EmojiUseCase;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * Replaces CE emoji for CustomNameplates' MiniMessage-based chat bubble.
     * White isolates the image glyph from the bubble/chat color surrounding it.
     */
    public static String resolveBubble(Player sender, String message) {
        if (sender == null || message == null || message.isEmpty()
                || !Bukkit.getPluginManager().isPluginEnabled("CraftEngine")) return message;
        try {
            var engine = BukkitCraftEngine.instance();
            if (engine == null || !engine.isFullyLoaded()) return message;
            var font = engine.fontManager();
            var player = BukkitAdaptor.adapt(sender);
            List<Replacement> replacements = new ArrayList<>();
            for (var emoji : font.emojis().values()) {
                for (String keyword : emoji.keywords()) {
                    if (keyword.isEmpty()) continue;
                    var parsed = font.replaceMiniMessageEmoji(keyword, player, EmojiUseCase.CHAT);
                    if (!parsed.replaced() || parsed.text().isEmpty()) continue;
                    replacements.add(new Replacement(keyword, "<white>" + parsed.text() + "</white>"));
                    var component = font.replaceComponentEmoji(
                            net.momirealms.craftengine.libraries.adventure.text.Component.text(keyword),
                            player, EmojiUseCase.CHAT);
                    if (component.changed()) {
                        String glyph = net.momirealms.craftengine.core.util.AdventureHelper.plainTextContent(
                                component.newText());
                        if (!glyph.isEmpty() && !glyph.equals(keyword))
                            replacements.add(new Replacement(glyph, "<white>" + parsed.text() + "</white>"));
                    }
                }
            }
            if (replacements.isEmpty()) return message;
            replacements.sort(Comparator.comparingInt((Replacement value) -> value.token().length()).reversed());

            StringBuilder result = new StringBuilder(message.length());
            int offset = 0;
            int count = 0;
            while (offset < message.length() && count < 16) {
                Replacement match = null;
                int index = message.length();
                for (Replacement candidate : replacements) {
                    int candidateIndex = message.indexOf(candidate.token(), offset);
                    if (candidateIndex >= 0 && (candidateIndex < index
                            || candidateIndex == index && (match == null
                            || candidate.token().length() > match.token().length()))) {
                        match = candidate;
                        index = candidateIndex;
                    }
                }
                if (match == null) break;
                result.append(message, offset, index).append(match.replacement());
                offset = index + match.token().length();
                count++;
            }
            return count == 0 ? message : result.append(message, offset, message.length()).toString();
        } catch (LinkageError | RuntimeException ex) {
            Bukkit.getLogger().warning("LiuChat: CraftEngine 气泡表情解析失败: " + ex);
            return message;
        }
    }

    private record Replacement(String token, String replacement) { }

    public static void addMatches(Map<String, String> results, String message, String keyword, String glyph, String encoded) {
        if (message.contains(keyword) && results.size() < 16) results.putIfAbsent(keyword, encoded);
        if (!glyph.isEmpty() && !glyph.equals(keyword) && message.contains(glyph) && results.size() < 16)
            results.putIfAbsent(glyph, encoded);
    }
}

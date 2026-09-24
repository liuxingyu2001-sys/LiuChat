package com.liu.liuchat.util;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;

/** Converts mixed legacy and MiniMessage formatting to client-compatible section codes. */
public final class ColorParser {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final MiniMessage PLAYER_MINI = MiniMessage.builder().tags(TagResolver.builder()
            .resolvers(StandardTags.color(), StandardTags.decorations(), StandardTags.gradient(),
                    StandardTags.rainbow(), StandardTags.reset()).build()).build();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build();
    private static final String[] COLORS = {
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
            "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple",
            "yellow", "white"};

    private ColorParser() { }

    public static String parse(String text) {
        if (text == null) return null;
        try {
            return LEGACY.serialize(MINI.deserialize(convert(text)));
        } catch (RuntimeException ex) {
            return ChatColor.translateAlternateColorCodes('&', text);
        }
    }

    public static String playerText(String input) {
        if (input == null) return "";
        try {
            return LEGACY.serialize(PLAYER_MINI.deserialize(convert(input)));
        } catch (RuntimeException ex) {
            return ChatColor.translateAlternateColorCodes('&', input);
        }
    }

    private static String convert(String source) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < source.length()) {
                char code = Character.toLowerCase(source.charAt(i + 1));
                if (code == '#' && i + 7 < source.length()
                        && source.substring(i + 2, i + 8).matches("[0-9a-fA-F]{6}")) {
                    out.append("<#").append(source, i + 2, i + 8).append('>');
                    i += 7;
                    continue;
                }
                if (code == 'x' && i + 13 < source.length()) {
                    StringBuilder hex = new StringBuilder();
                    for (int j = 0; j < 6; j++) {
                        int pos = i + 2 + j * 2;
                        if ((source.charAt(pos) != '§' && source.charAt(pos) != '&')
                                || Character.digit(source.charAt(pos + 1), 16) < 0) break;
                        hex.append(source.charAt(pos + 1));
                    }
                    if (hex.length() == 6) {
                        out.append("<#").append(hex).append('>');
                        i += 13;
                        continue;
                    }
                }
                int digit = Character.digit(code, 16);
                String tag = digit >= 0 ? COLORS[digit] : switch (code) {
                    case 'k' -> "obfuscated";
                    case 'l' -> "bold";
                    case 'm' -> "strikethrough";
                    case 'n' -> "underlined";
                    case 'o' -> "italic";
                    case 'r' -> "reset";
                    default -> null;
                };
                if (tag != null) {
                    out.append('<').append(tag).append('>');
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }
}

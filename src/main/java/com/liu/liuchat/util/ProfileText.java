package com.liu.liuchat.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Normalizes profile text while dropping interactive MiniMessage events. */
public final class ProfileText {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.legacyAmpersand();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Pattern LEGACY_CODES = Pattern.compile("(?i)&([0-9a-fk-or])");
    private static final Pattern HEX = Pattern.compile("(?i)&#([0-9a-f]{6})");
    private static final Map<Character, String> LEGACY_TAGS = Map.ofEntries(
            Map.entry('0', "black"), Map.entry('1', "dark_blue"), Map.entry('2', "dark_green"),
            Map.entry('3', "dark_aqua"), Map.entry('4', "dark_red"), Map.entry('5', "dark_purple"),
            Map.entry('6', "gold"), Map.entry('7', "gray"), Map.entry('8', "dark_gray"),
            Map.entry('9', "blue"), Map.entry('a', "green"), Map.entry('b', "aqua"),
            Map.entry('c', "red"), Map.entry('d', "light_purple"), Map.entry('e', "yellow"),
            Map.entry('f', "white"), Map.entry('k', "obfuscated"), Map.entry('l', "bold"),
            Map.entry('m', "strikethrough"), Map.entry('n', "underlined"), Map.entry('o', "italic"),
            Map.entry('r', "reset"));

    private ProfileText() { }

    public static String normalize(String input, boolean formatted) {
        if (input == null || input.equalsIgnoreCase("off")) return "";
        if (!formatted) return PLAIN.serialize(AMPERSAND.deserialize(input));
        return LEGACY.serialize(MINI.deserialize(toMiniMessage(input)));
    }

    private static String toMiniMessage(String input) {
        Matcher hex = HEX.matcher(input);
        StringBuffer converted = new StringBuffer();
        while (hex.find()) hex.appendReplacement(converted, "<#$1>");
        hex.appendTail(converted);
        Matcher legacy = LEGACY_CODES.matcher(converted.toString());
        StringBuffer result = new StringBuffer();
        while (legacy.find()) {
            String tag = LEGACY_TAGS.get(legacy.group(1).toLowerCase(Locale.ROOT).charAt(0));
            legacy.appendReplacement(result, Matcher.quoteReplacement(tag == null ? "" : "<" + tag + ">"));
        }
        legacy.appendTail(result);
        return result.toString();
    }
}

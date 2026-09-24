package com.liu.liuchat.service;

import com.liu.liuchat.util.TextUtil;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Applies a saved profile gradient without parsing player text as MiniMessage markup. */
final class ProfileChatColor {
    private static final Pattern GRADIENT = Pattern.compile(
            "(?i)^<gradient:#([0-9a-f]{6}):#([0-9a-f]{6})>(.*)$", Pattern.DOTALL);

    private ProfileChatColor() { }

    static String apply(String format, String message, String itemToken) {
        Matcher matcher = GRADIENT.matcher(format);
        if (!matcher.matches()) {
            String prefix = TextUtil.color(format + "x");
            return prefix.endsWith("x") ? prefix.substring(0, prefix.length() - 1) + message : message;
        }
        int start = Integer.parseInt(matcher.group(1), 16);
        int end = Integer.parseInt(matcher.group(2), 16);
        String styles = TextUtil.color(matcher.group(3) + "x");
        if (styles.endsWith("x")) styles = styles.substring(0, styles.length() - 1);
        StringBuilder colored = new StringBuilder(message.length() * 16);
        int count = 0;
        for (int i = 0; i < message.length();) {
            if (!itemToken.isEmpty() && message.startsWith(itemToken, i)) {
                i += itemToken.length();
                continue;
            }
            if (message.charAt(i) == '§' && i + 1 < message.length()) {
                i += 2;
                continue;
            }
            count++;
            i += Character.charCount(message.codePointAt(i));
        }
        int position = 0;
        for (int i = 0; i < message.length();) {
            if (!itemToken.isEmpty() && message.startsWith(itemToken, i)) {
                colored.append(itemToken);
                i += itemToken.length();
            } else if (message.charAt(i) == '§' && i + 1 < message.length()) {
                // Preserve style codes but let the profile gradient supply the actual color.
                char code = Character.toLowerCase(message.charAt(i + 1));
                if ("klmno".indexOf(code) >= 0) colored.append(message, i, i + 2);
                i += 2;
            } else {
                int codepoint = message.codePointAt(i);
                float fraction = count < 2 ? 0 : (float) position / (count - 1);
                int color = 0;
                for (int shift : new int[]{16, 8, 0}) {
                    int from = start >>> shift & 255;
                    int to = end >>> shift & 255;
                    color |= Math.round(from + (to - from) * fraction) << shift;
                }
                colored.append(hexCode(color)).append(styles);
                colored.appendCodePoint(codepoint);
                position++;
                i += Character.charCount(codepoint);
            }
        }
        return colored.toString();
    }

    private static String hexCode(int color) {
        String digits = String.format("%06X", color);
        StringBuilder code = new StringBuilder("§x");
        for (int i = 0; i < digits.length(); i++) code.append('§').append(digits.charAt(i));
        return code.toString();
    }
}

package com.liu.liuchat.service;

import com.liu.liuchat.util.TextUtil;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;

import java.util.regex.Pattern;

/**
 * 外部聊天（QQ 等）的固定格式渲染：模板先上色，正文最后插入。
 * 正文里的颜色码/占位符不会被解释，避免外部消息注入游戏样式。
 */
public final class ExternalChatText {
    private static final Pattern SECTION_CODE = Pattern.compile("(?i)§.");
    private static final Pattern SPACES = Pattern.compile("\\s{2,}");

    private ExternalChatText() { }

    public static String line(String format, String sender, String message, String source, String target) {
        String template = TextUtil.color(format == null || format.isBlank()
                ? "&8[&9QQ&8] &b${sender}&8» &f${message}" : format);
        boolean hasMessage = template.contains("${message}");
        String safeSender = oneLine(SECTION_CODE.matcher(sender == null ? "" : sender).replaceAll(""), 64);
        String safeSource = oneLine(SECTION_CODE.matcher(source == null ? "" : source).replaceAll(""), 32);
        String safeTarget = oneLine(SECTION_CODE.matcher(target == null ? "" : target).replaceAll(""), 128);
        String body = oneLine(SECTION_CODE.matcher(message == null ? "" : message).replaceAll(""), 4000);
        template = template.replace("${sender}", safeSender)
                .replace("${player}", safeSender)
                .replace("${source}", safeSource)
                .replace("${target}", safeTarget)
                .replace("${group}", safeTarget);
        if (hasMessage) return template.replace("${message}", body);
        return template + (template.endsWith(" ") || template.isEmpty() ? "" : " ") + body;
    }

    public static BaseComponent[] components(String format, String sender, String message,
                                             String source, String target) {
        return TextComponent.fromLegacyText(line(format, sender, message, source, target));
    }

    private static String oneLine(String text, int max) {
        String value = text == null ? "" : text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        value = SPACES.matcher(value).replaceAll(" ").strip();
        if (value.codePointCount(0, value.length()) <= max) return value;
        return value.substring(0, value.offsetByCodePoints(0, Math.max(0, max - 1))).stripTrailing();
    }
}

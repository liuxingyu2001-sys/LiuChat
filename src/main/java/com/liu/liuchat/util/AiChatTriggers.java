package com.liu.liuchat.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 公屏 AI 聊天的触发判定与提问组装（纯字符串逻辑）。
 */
public final class AiChatTriggers {

    private AiChatTriggers() { }

    /** 跨服聊天保留发送端的 § 颜色码；点名和 AI 上下文必须使用玩家可见的文本。 */
    public static String visibleText(String message) {
        if (message == null || message.indexOf('§') < 0) return message;
        StringBuilder visible = new StringBuilder(message.length());
        for (int i = 0; i < message.length(); i++) {
            if (message.charAt(i) == '§' && i + 1 < message.length()) {
                i++;
            } else {
                visible.append(message.charAt(i));
            }
        }
        return visible.toString();
    }

    /**
     * 消息是否点名了 AI：{@code @名字} 或消息里直接提到名字，忽略大小写。
     * ASCII 名字按词边界匹配（Steve 不会命中 Steve2 / MySteve），中文名按子串匹配。
     */
    public static boolean triggers(String message, String aiName) {
        if (message == null || message.isBlank() || aiName == null || aiName.isBlank()) return false;
        Pattern pattern = Pattern.compile("(?<![A-Za-z0-9_@])@?" + Pattern.quote(aiName.trim())
                + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE);
        return pattern.matcher(message).find();
    }

    /** 配置的关键词按字面匹配（忽略英文大小写），不把关键词当正则执行。 */
    public static boolean matchesKeyword(String message, List<String> keywords) {
        if (message == null || keywords == null) return false;
        String text = message.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank()
                    && text.contains(keyword.trim().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    public static String composeProactiveQuestion(List<String> recent, String aiName, String prompt, int maxLength) {
        String head = "你是服务器公屏聊天里的玩家 " + aiName + "。最近的公屏消息：\n";
        String tail = "\n请以 " + aiName + " 的身份主动发一条简短的公屏消息，不要颜色代码、不要换行。"
                + (prompt == null ? "" : prompt);
        List<String> lines = new ArrayList<>(recent == null ? List.of() : recent);
        while (true) {
            StringBuilder out = new StringBuilder(head);
            for (String line : lines) out.append(line).append('\n');
            out.append(tail);
            if (maxLength <= 0 || out.length() <= maxLength) return out.toString();
            if (!lines.isEmpty()) lines.remove(0);
            else return out.substring(0, maxLength);
        }
    }

    /**
     * 组装提问：最近的公屏消息作为聊天氛围上下文 + 本次点名消息 + 回复要求。
     * 超过 maxLength 时从最旧的上下文行开始丢；仍超长则截断整段（保证不超 max-question）。
     *
     * @param recent 不含本次消息的最近公屏消息（形如 {@code 玩家: 内容}）
     */
    public static String composeQuestion(List<String> recent, String speaker, String message,
                                         String aiName, int maxLength) {
        String head = "你是服务器公屏聊天里的玩家 " + aiName + "。最近的公屏消息：\n";
        String tail = speaker + " 说: " + message
                + "\n请以 " + aiName + " 的身份像普通 Minecraft 玩家那样简短回复，"
                + "不要颜色代码、不要换行、不要复述对方的话。";
        List<String> lines = new ArrayList<>(recent == null ? List.of() : recent);
        while (true) {
            StringBuilder sb = new StringBuilder(head);
            for (String line : lines) sb.append(line).append('\n');
            sb.append(tail);
            if (maxLength <= 0 || sb.length() <= maxLength) return sb.toString();
            if (!lines.isEmpty()) {
                lines.remove(0);
                continue;
            }
            String question = sb.toString();
            return question.substring(0, Math.min(question.length(), maxLength));
        }
    }
}

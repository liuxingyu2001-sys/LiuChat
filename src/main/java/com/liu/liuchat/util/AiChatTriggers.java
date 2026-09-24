package com.liu.liuchat.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 公屏 AI 聊天的触发判定与提问组装（纯字符串逻辑）。
 */
public final class AiChatTriggers {

    private AiChatTriggers() { }

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

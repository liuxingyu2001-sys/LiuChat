package com.liu.liuchat.hook.qq;

import com.liu.liuchat.util.TextUtil;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;

import java.util.regex.Pattern;

/**
 * QQ 与游戏之间的文本清洗 / 格式化。
 *
 * <p>两边的“用户可控文本”都要最后插入，且先清掉颜色码：QQ 消息不能借
 * {@code &}/{@code §} 给游戏内文字上色，游戏消息也不能把颜色码带进群聊。
 * 全部方法不依赖 Bukkit，可直接单元测试。
 */
public final class QqBotText {

    /** QQ 频道消息里的 @ 标签：{@code <@!123>} / {@code <@123>} */
    private static final Pattern MENTION_TAG = Pattern.compile("<@!?\\d+>");
    /** § 颜色/样式码（含 §x 十六进制序列）。 */
    private static final Pattern SECTION_CODE = Pattern.compile("(?i)§.");
    /** 发到 QQ 的文字里残留的 & 颜色码（含 &#RRGGBB / &xRRGGBB 十六进制）。 */
    private static final Pattern AMPERSAND_CODE = Pattern.compile(
            "(?i)&#[0-9a-f]{6}|&x(?:§?[0-9a-f]){6}|&[0-9a-fk-or]");
    /** 连续空白压成单个空格。 */
    private static final Pattern SPACES = Pattern.compile("\\s{2,}");

    public static final String DEFAULT_RECEIVE_FORMAT = "&8[&9QQ&8] &b${sender}&8» &f${message}";
    public static final String DEFAULT_SEND_FORMAT = "[${server}] ${player}: ${message}";
    public static final String DEFAULT_FALLBACK_NAME = "QQ用户";

    private QqBotText() {
    }

    /** 去掉频道 @ 标签并压缩空白，保留正文里的 &（游戏侧不会按颜色解析）。 */
    public static String cleanIncoming(String content, int maxLength) {
        if (content == null) return "";
        String value = MENTION_TAG.matcher(content).replaceAll("");
        return clean(value, maxLength);
    }

    /** 发给 QQ 的正文：去颜色码、换行压成空格、按长度截断。 */
    public static String cleanOutgoing(String content, int maxLength) {
        if (content == null) return "";
        String value = AMPERSAND_CODE.matcher(SECTION_CODE.matcher(content).replaceAll("")).replaceAll("");
        return clean(value, maxLength);
    }

    /** 清掉 § 颜色码，避免昵称/会话标签注入样式。 */
    public static String stripSection(String text) {
        return text == null ? "" : SECTION_CODE.matcher(text).replaceAll("");
    }

    /**
     * 渲染 QQ -> 游戏的固定格式。模板先按 & / MiniMessage 上色，消息正文最后插入，
     * 因此正文里的颜色码/占位符不会被解释。
     */
    public static String incomingLine(String format, String sender, String message, String source, String target,
                                      int maxLength) {
        String template = TextUtil.color(format == null || format.isBlank() ? DEFAULT_RECEIVE_FORMAT : format);
        boolean hasMessage = template.contains("${message}");
        String safeSender = oneLine(stripSection(sender), 64);
        String safeSource = oneLine(stripSection(source), 32);
        String safeTarget = oneLine(stripSection(target), 128);
        String body = cleanIncoming(message, maxLength);
        template = template.replace("${sender}", safeSender)
                .replace("${player}", safeSender)
                .replace("${source}", safeSource)
                .replace("${target}", safeTarget)
                .replace("${group}", safeTarget);
        if (!hasMessage) {
            return template + body;
        }
        return template.replace("${message}", body);
    }

    public static BaseComponent[] incomingComponents(String format, String sender, String message,
                                                     String source, String target, int maxLength) {
        return TextComponent.fromLegacyText(incomingLine(format, sender, message, source, target, maxLength));
    }

    /** 渲染游戏 -> QQ 的纯文本；颜色码会被清掉，正文最后插入。 */
    public static String outgoingLine(String format, String server, String player, String message, int maxLength) {
        String template = format == null || format.isBlank() ? DEFAULT_SEND_FORMAT : format;
        String safeServer = oneLine(stripSection(server), 64);
        String safePlayer = oneLine(stripSection(player), 64);
        String body = cleanOutgoing(message, maxLength);
        template = template.replace("${server}", safeServer)
                .replace("${player}", safePlayer)
                .replace("${nick}", safePlayer)
                .replace("${message}", body);
        if (!format.contains("${message}") && format != null && !format.isBlank()) {
            template = template + (template.endsWith(" ") || template.isEmpty() ? "" : " ") + body;
        }
        return cleanOutgoing(template, maxLength);
    }

    /**
     * 群聊/私聊拿不到昵称时生成稳定短名：{@code QQ用户·A1B2C3}。
     * 只保留 ID 尾部，避免把完整 openid 铺满聊天栏。
     */
    public static String fallbackSender(String fallbackName, String senderId) {
        String prefix = fallbackName == null || fallbackName.isBlank() ? DEFAULT_FALLBACK_NAME : fallbackName.strip();
        prefix = stripSection(prefix);
        if (prefix.isEmpty()) prefix = DEFAULT_FALLBACK_NAME;
        String id = senderId == null ? "" : senderId.strip();
        if (id.isEmpty()) return prefix;
        int count = id.codePointCount(0, id.length());
        int keep = Math.min(6, count);
        String tail = id.substring(id.offsetByCodePoints(0, count - keep));
        return prefix + "·" + tail;
    }

    /** 单行化 + 去 § + 截断。 */
    private static String clean(String text, int maxLength) {
        String value = oneLine(stripSection(text), Integer.MAX_VALUE);
        int max = Math.max(1, maxLength);
        int count = value.codePointCount(0, value.length());
        if (count <= max) return value;
        int end = value.offsetByCodePoints(0, Math.max(0, max - 1));
        return value.substring(0, end).stripTrailing() + "…";
    }

    private static String oneLine(String text, int maxLength) {
        if (text == null) return "";
        String value = text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        value = SPACES.matcher(value).replaceAll(" ").strip();
        int max = Math.max(1, maxLength);
        if (value.codePointCount(0, value.length()) <= max) return value;
        return value.substring(0, value.offsetByCodePoints(0, Math.max(0, max - 1))).stripTrailing();
    }
}

package com.liu.liuchat.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formats assistant output for Minecraft chat without Markdown layout.
 * <p>
 * 只剥会破坏可读性的 Markdown 记号（围栏、行首标题符、成对的强调符），正文符号一律保留：
 * 指令占位符 {@code <名称>} 里的 {@code >}、{@code /tp ~ ~ ~}、{@code player_name} 都不能被吃掉。
 * 折行按显示宽度算（全角算 2 个半角），并且保留原始换行与段落空行，
 * 方便聊天框把多段回答拼成一条消息一次发出去（见 {@link #block}）。
 */
public final class AiAnswerFormatter {
    private static final Pattern CODE_FENCE = Pattern.compile("```[^\\n]*\\n?|```");
    /** 成对出现才算强调符；不成对的 * _ ~ # > 是正文，删了会毁掉指令和占位符 */
    private static final Pattern EMPHASIS = Pattern.compile(
            "\\*{3}([^*\\n]+?)\\*{3}|\\*\\*([^*\\n]+?)\\*\\*|\\*([^*\\n]+?)\\*"
                    + "|__([^_\\n]+?)__|_([^_\\n]+?)_|~~([^~\\n]+?)~~|`([^`\\n]+?)`");
    /** 行首才是 Markdown 标题，正文里的 #（例如 #RRGGBB）不动 */
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s*");
    private static final Pattern COMMAND = Pattern.compile("(?<!\\S)(/[-a-zA-Z0-9_:]+(?:\\s+[^\\s]+){0,8})");
    private static final Pattern COLOR_CODE = Pattern.compile("(?i)(?:[§&]x(?:[§&][0-9a-f]){6})|[§&][0-9a-fk-or]");
    /** 一行的宽度预算（半角单位，全角算 2），对齐聊天框 320px 的可用宽度 */
    private static final int MAX_WIDTH = 46;
    private static final int MIN_WIDTH = 8;
    private static final String EMPTY_HINT = "&7（助手没有返回内容）";

    private AiAnswerFormatter() { }

    /** 逐行输出（Dialog 用）：段落之间的空行丢弃，一行一条 */
    public static List<String> lines(String answer) {
        List<String> out = new ArrayList<>();
        for (String line : wrap(answer, MAX_WIDTH)) if (!line.isEmpty()) out.add(line);
        return out.isEmpty() ? List.of(EMPTY_HINT) : List.copyOf(out);
    }

    /**
     * 聊天框一次性发送的多行文本：原始换行与段落之间的空行都保留。
     *
     * @param reserved 首行前缀占的显示宽度，正文给它让位，避免前缀把内容挤出屏幕
     */
    public static String block(String answer, int reserved) {
        List<String> out = wrap(answer, Math.max(MIN_WIDTH, MAX_WIDTH - Math.max(0, reserved)));
        return out.isEmpty() ? EMPTY_HINT : String.join("\n", out);
    }

    /** 去掉颜色码后的显示宽度（半角单位，全角算 2） */
    public static int visibleWidth(String text) {
        return text == null || text.isEmpty() ? 0 : width(COLOR_CODE.matcher(text).replaceAll(""));
    }

    private static List<String> wrap(String answer, int firstWidth) {
        String source = answer == null ? "" : CODE_FENCE.matcher(answer).replaceAll("");
        source = source.replace("\r\n", "\n").replace('\r', '\n');
        List<String> out = new ArrayList<>();
        boolean started = false;
        boolean gap = false;
        for (String raw : source.split("\n", -1)) {
            String text = stripLine(raw);
            if (text.isEmpty()) {
                if (started) gap = true; // 只在已有正文之后留一个空行当段落分隔
                continue;
            }
            if (gap) {
                out.add("");
                gap = false;
            }
            emit(out, text, started ? MAX_WIDTH : firstWidth);
            started = true;
        }
        return out;
    }

    /**
     * 一行里可能有指令要高亮：短引导（序号、项目符号）尽量和指令同一行，
     * 指令段用 {@code &f} 复位 —— 整段回答现在是一条消息，不复位颜色会一路染到最后。
     */
    private static void emit(List<String> out, String text, int firstWidth) {
        Matcher command = COMMAND.matcher(text);
        if (!command.find()) {
            addWrapped(out, text, "", firstWidth, MAX_WIDTH);
            return;
        }
        String before = text.substring(0, command.start()).strip();
        String tail = text.substring(command.end());
        String cmd = command.group(1).strip();
        if (!before.isEmpty() && width(before) + 1 + width(cmd) <= firstWidth) {
            out.add(before + " &b" + cmd);
        } else {
            if (!before.isEmpty()) addWrapped(out, before, "", firstWidth, MAX_WIDTH);
            addWrapped(out, cmd, "&b", before.isEmpty() ? firstWidth : MAX_WIDTH, MAX_WIDTH);
        }
        int last = out.size() - 1;
        String tailText = tail.strip();
        int gap = tail.startsWith(" ") ? 1 : 0;
        // 尾巴放得下就接回同一行，省得冒出只有句号的一行；尾巴里还有指令就继续拆
        if (!tail.isBlank() && !COMMAND.matcher(tail).find()
                && visibleWidth(out.get(last)) + gap + width(tailText) <= MAX_WIDTH)
            out.set(last, out.get(last) + "&f" + " ".repeat(gap) + tailText);
        else {
            out.set(last, out.get(last) + "&f");
            if (!tail.isBlank()) emit(out, tailText, MAX_WIDTH);
        }
    }

    private static void addWrapped(List<String> out, String text, String prefix, int firstWidth, int lineWidth) {
        if (text.isEmpty()) return;
        int indent = visibleWidth(prefix);
        int budget = Math.max(MIN_WIDTH, firstWidth - indent);
        String remaining = text;
        while (width(remaining) > budget) {
            int cut = cutAt(remaining, budget);
            out.add(prefix + remaining.substring(0, cut).strip());
            remaining = remaining.substring(cut).strip();
            budget = Math.max(MIN_WIDTH, lineWidth - indent);
        }
        if (!remaining.isEmpty()) out.add(prefix + remaining);
    }

    /** 按显示宽度找断点：空格断行要占掉至少半行才认，免得 "- " 这种短词把一行切碎 */
    private static int cutAt(String text, int budget) {
        int total = 0;
        int space = -1;
        int spaceWidth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ') {
                space = i;
                spaceWidth = total;
            }
            total += weight(c);
            if (total > budget) {
                if (space > 0 && spaceWidth * 2 >= budget) return space;
                return Math.max(1, i);
            }
        }
        return text.length();
    }

    /** 去掉 Markdown 记号，保留正文符号 */
    private static String stripLine(String raw) {
        String text = HEADING.matcher(raw.strip()).replaceFirst("");
        text = emphasis(text);
        // 不闭合的成对标号直接去掉，单个 * _ # > 保留为正文
        text = text.replace("**", "").replace("__", "").replace("~~", "").replace("`", "");
        return text.strip();
    }

    private static String emphasis(String text) {
        Matcher matcher = EMPHASIS.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String content = null;
            for (int group = 1; group <= matcher.groupCount(); group++) {
                if (matcher.group(group) != null) {
                    content = matcher.group(group);
                    break;
                }
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(content == null ? matcher.group() : content));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static int width(String text) {
        int total = 0;
        for (int i = 0; i < text.length(); i++) total += weight(text.charAt(i));
        return total;
    }

    private static int weight(char c) {
        return isWide(c) ? 2 : 1;
    }

    /** 全角/假名/谚文按 2 个半角宽度算，折行才不会把中文行顶出聊天框 */
    private static boolean isWide(char c) {
        return c >= 0x1100 && (c <= 0x115F || c == 0x2329 || c == 0x232A
                || (c >= 0x2E80 && c <= 0xA4CF && c != 0x303F)
                || (c >= 0xAC00 && c <= 0xD7A3) || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFE30 && c <= 0xFE6F) || (c >= 0xFF00 && c <= 0xFF60)
                || (c >= 0xFFE0 && c <= 0xFFE6));
    }
}

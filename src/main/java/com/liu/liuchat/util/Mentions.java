package com.liu.liuchat.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @ 提及：把消息里的玩家 ID 标成「@玩家」并按配置高亮，同时返回被 @ 的名字（用于给被 @ 玩家播放提示音）。
 *
 * <p>两种写法：<code>@玩家ID</code>，以及直接输入玩家 ID（自动补 @，{@code keepAt} 控制是否显示 @）。
 * 高亮在玩家文本颜色权限裁决（liuchat.color）之后注入，因此没有颜色权限的玩家 @ 人同样会变色。
 * 邮箱、网址里的 @ 不会被误判（前后不允许紧跟字母数字下划线或 @）。
 */
public final class Mentions {

    /** @玩家ID（不要求在线：跨服/离线的 ID 也会高亮） */
    private static final Pattern AT_MENTION =
            Pattern.compile("(?<![A-Za-z0-9_@])@([A-Za-z0-9_]{1,32})(?![A-Za-z0-9_@])");
    /** 直接输入在线玩家 ID 时自动补 @ */
    private static final String BARE_MENTION = "(?<![A-Za-z0-9_@])(?:%s)(?![A-Za-z0-9_@])";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,32}");
    /** 参与自动补 @ 的名字数量上限，避免正则无限膨胀 */
    private static final int MAX_NAMES = 512;
    private static final String STYLE_CODES = "klmno";

    private Mentions() { }

    /** 标记结果：text 为高亮后的消息，names 为被 @ 的玩家 ID（按出现顺序、大小写不敏感去重）。 */
    public record Marked(String text, List<String> names) {
        public boolean mentions(String name) {
            if (name == null) return false;
            for (String seen : names) if (seen.equalsIgnoreCase(name)) return true;
            return false;
        }
    }

    private record Span(int start, int end, String name) { }

    /**
     * 标记消息里的玩家 ID。
     *
     * @param message    已按发送者权限处理完颜色（§ 码）的消息
     * @param knownNames 在线玩家 ID（本服 + 跨服），用于「输入玩家 ID 自动补 @」
     * @param keepAt     是否保留/补出 @ 符号
     * @param highlight  高亮颜色（§ 码，可为空表示不高亮）
     */
    public static Marked mark(String message, Collection<String> knownNames, boolean keepAt, String highlight) {
        if (message == null || message.isEmpty()) {
            return new Marked(message == null ? "" : message, List.of());
        }
        String color = highlight == null ? "" : highlight;
        int length = message.length();
        // 1) 去掉颜色码得到纯文本，并记录纯文本下标 -> 原始下标的映射
        StringBuilder clean = new StringBuilder(length);
        int[] map = new int[length + 1];
        for (int i = 0; i < length; ) {
            char c = message.charAt(i);
            if (c == '§' && i + 1 < length) {
                i += 2;
                continue;
            }
            if (c == '&' && i + 1 < length && isColorCode(message.charAt(i + 1))) {
                i += 2;
                continue;
            }
            map[clean.length()] = i;
            clean.append(c);
            i++;
        }
        map[clean.length()] = length;

        // 2) 找出所有提及（@玩家 优先于裸玩家 ID，重叠只取先出现的）
        List<Span> spans = new ArrayList<>();
        Matcher at = AT_MENTION.matcher(clean);
        while (at.find()) spans.add(new Span(at.start(), at.end(), at.group(1)));
        Pattern bare = barePattern(knownNames);
        if (bare != null) {
            Matcher matcher = bare.matcher(clean);
            while (matcher.find()) spans.add(new Span(matcher.start(), matcher.end(), matcher.group()));
        }
        if (spans.isEmpty()) return new Marked(message, List.of());
        spans.sort((left, right) -> left.start() != right.start()
                ? Integer.compare(left.start(), right.start())
                : Integer.compare(right.end(), left.end()));
        List<Span> picked = new ArrayList<>();
        int boundary = 0;
        for (Span span : spans) {
            if (span.start() < boundary) continue;
            picked.add(span);
            boundary = span.end();
        }

        // 3) 重建消息：提及处换成高亮文本，其后的颜色/样式状态原样续上
        StringBuilder out = new StringBuilder(length + picked.size() * 12);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> names = new ArrayList<>();
        int rawPos = 0;
        for (Span span : picked) {
            int rawStart = map[span.start()];
            int rawEnd = map[span.end()];
            out.append(message, rawPos, rawStart);
            out.append(color).append(keepAt ? "@" : "").append(span.name());
            if (!color.isEmpty()) {
                out.append("§r");
                String state = stateAt(message, rawEnd);
                if (!state.isEmpty()) out.append(state);
            }
            if (seen.add(span.name().toLowerCase(Locale.ROOT))) names.add(span.name());
            rawPos = rawEnd;
        }
        out.append(message, rawPos, length);
        return new Marked(out.toString(), List.copyOf(names));
    }

    /** 文本末尾生效的颜色与样式状态（§ 码），用于在插入高亮后恢复原样式。 */
    public static String state(String text) {
        return text == null ? "" : stateAt(text, text.length());
    }

    private static String stateAt(String raw, int end) {
        String color = "";
        boolean[] styles = new boolean[STYLE_CODES.length()];
        int limit = Math.min(end, raw.length());
        for (int i = 0; i < limit; ) {
            char c = raw.charAt(i);
            if (c != '§' || i + 1 >= raw.length()) {
                i++;
                continue;
            }
            char code = Character.toLowerCase(raw.charAt(i + 1));
            if (code == 'x') {
                int j = i + 2;
                StringBuilder hex = new StringBuilder("§x");
                int need = 6;
                while (need > 0 && j + 1 < raw.length() && raw.charAt(j) == '§'
                        && Character.digit(raw.charAt(j + 1), 16) >= 0) {
                    hex.append('§').append(raw.charAt(j + 1));
                    j += 2;
                    need--;
                }
                if (need == 0) {
                    color = hex.toString();
                    i = j;
                    continue;
                }
                i += 2;
                continue;
            }
            if (Character.digit(code, 16) >= 0) {
                color = "§" + raw.charAt(i + 1);
                i += 2;
                continue;
            }
            if (code == 'r') {
                color = "";
                Arrays.fill(styles, false);
                i += 2;
                continue;
            }
            int style = STYLE_CODES.indexOf(code);
            if (style >= 0) styles[style] = true;
            i += 2;
        }
        StringBuilder out = new StringBuilder(color);
        for (int i = 0; i < styles.length; i++) {
            if (styles[i]) out.append('§').append(STYLE_CODES.charAt(i));
        }
        return out.toString();
    }

    private static Pattern barePattern(Collection<String> knownNames) {
        if (knownNames == null || knownNames.isEmpty()) return null;
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String name : knownNames) {
            if (name == null || !NAME.matcher(name).matches()) continue;
            names.add(name.toLowerCase(Locale.ROOT));
        }
        if (names.isEmpty()) return null;
        List<String> sorted = new ArrayList<>(names);
        // 长名优先，避免短名把长名切开
        sorted.sort((left, right) -> right.length() - left.length());
        if (sorted.size() > MAX_NAMES) sorted = sorted.subList(0, MAX_NAMES);
        StringBuilder alternation = new StringBuilder();
        for (String name : sorted) {
            if (alternation.length() > 0) alternation.append('|');
            alternation.append(Pattern.quote(name));
        }
        return Pattern.compile(String.format(BARE_MENTION, alternation), Pattern.CASE_INSENSITIVE);
    }

    /** & 后跟颜色/样式码才算颜色码（玩家输入里剩下的 & 是字面文本） */
    private static boolean isColorCode(char c) {
        char code = Character.toLowerCase(c);
        return Character.digit(code, 16) >= 0 || STYLE_CODES.indexOf(code) >= 0 || code == 'r' || code == 'x';
    }
}

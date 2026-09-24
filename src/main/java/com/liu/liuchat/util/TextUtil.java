package com.liu.liuchat.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本工具：颜色翻译 / 时长解析与格式化 / 相似度计算。
 */
public final class TextUtil {

    private static final Pattern DURATION_TOKEN = Pattern.compile("(\\d+)([smhd])");

    private TextUtil() {
    }

    /** & 颜色码转 §（只认 &，所以插入到本方法之后的玩家文本不受影响） */
    public static String color(String text) {
        return ColorParser.parse(text);
    }

    /**
     * 解析时长。
     *
     * @return 毫秒数；0 = 永久（"0"/"perma"/"permanent"/"永久"）；-1 = 格式非法
     */
    public static long parseDuration(String input) {
        if (input == null) {
            return -1;
        }
        String s = input.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return -1;
        }
        if (s.equals("0") || s.equals("perma") || s.equals("permanent") || s.equals("永久")) {
            return 0;
        }
        try {
            // 纯数字按秒处理
            if (s.chars().allMatch(Character::isDigit)) {
                long seconds = Long.parseLong(s);
                return seconds > 0 ? seconds * 1000L : -1;
            }
            // 组合格式："5m" / "1h30m" / "2d"
            Matcher matcher = DURATION_TOKEN.matcher(s);
            long total = 0;
            int end = 0;
            boolean matched = false;
            while (matcher.find()) {
                if (matcher.start() != end) {
                    // 合法片段之间夹了非法字符
                    return -1;
                }
                end = matcher.end();
                long value = Long.parseLong(matcher.group(1));
                total += switch (matcher.group(2)) {
                    case "s" -> value * 1000L;
                    case "m" -> value * 60_000L;
                    case "h" -> value * 3_600_000L;
                    default -> value * 86_400_000L;
                };
                matched = true;
            }
            if (!matched || end != s.length() || total <= 0) {
                return -1;
            }
            return total;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 毫秒转人话时长："1天2小时" / "5分30秒" */
    public static String formatDuration(long millis) {
        if (millis <= 0) {
            return "<1秒";
        }
        long seconds = millis / 1000;
        long days = seconds / 86400;
        long hours = seconds % 86400 / 3600;
        long minutes = seconds % 3600 / 60;
        long secs = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("天");
        }
        if (hours > 0) {
            sb.append(hours).append("小时");
        }
        if (minutes > 0) {
            sb.append(minutes).append("分");
        }
        if (secs > 0 && days == 0) {
            sb.append(secs).append("秒");
        }
        return sb.isEmpty() ? "<1秒" : sb.toString();
    }

    /** 两个字符串的相似度，0~100（忽略大小写） */
    public static double similarity(String a, String b) {
        int maxLength = Math.max(a.length(), b.length());
        if (maxLength == 0) {
            return 100;
        }
        int distance = levenshtein(a.toLowerCase(Locale.ROOT), b.toLowerCase(Locale.ROOT));
        return (1.0 - (double) distance / maxLength) * 100.0;
    }

    /** 经典 Levenshtein 编辑距离，两行滚动数组 O(n*m) */
    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }
}

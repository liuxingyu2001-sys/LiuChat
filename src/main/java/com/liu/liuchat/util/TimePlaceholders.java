package com.liu.liuchat.util;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 模板里的时间/日期变量替换（消息提醒、聊天日志等需要“当前时间”的文本）。
 *
 * <p>支持两类写法：
 * <ul>
 *   <li>简写：{@code ${time}}（默认 {@code HH:mm:ss}）、{@code ${date}}（默认
 *       {@code yyyy-MM-dd}）、{@code ${datetime}}（默认 {@code yyyy-MM-dd HH:mm:ss}）、
 *       {@code ${year}}、{@code ${month}}、{@code ${day}}、{@code ${hour}}、
 *       {@code ${minute}}、{@code ${second}}、{@code ${weekday}}（周一…周日）；</li>
 *   <li>自定义格式：{@code ${time:HH:mm}}、{@code ${date:yyyy年MM月dd日}}、
 *       {@code ${datetime:yyyy/MM/dd HH:mm:ss}}，冒号后接 {@link DateTimeFormatter} 模式。</li>
 * </ul>
 *
 * <p>无法解析的 pattern 会替换成空串，不抛异常、不影响整条消息发送。
 * 时区由调用方决定（提醒用计划时区，日志用服务器时区）。
 */
public final class TimePlaceholders {
    private static final String DEFAULT_TIME = "HH:mm:ss";
    private static final String DEFAULT_DATE = "yyyy-MM-dd";
    private static final String DEFAULT_DATETIME = "yyyy-MM-dd HH:mm:ss";
    private static final String[] WEEKDAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private TimePlaceholders() { }

    /** 替换模板中的全部时间变量；{@code time} 为空或无变量时原样返回。 */
    public static String apply(String template, ZonedDateTime time) {
        if (template == null || template.isEmpty() || time == null || template.indexOf('$') < 0) return template;
        String out = replacePattern(template, "${time:", time, DEFAULT_TIME);
        out = replacePattern(out, "${date:", time, DEFAULT_DATE);
        out = replacePattern(out, "${datetime:", time, DEFAULT_DATETIME);
        out = out.replace("${time}", format(time, DEFAULT_TIME))
                .replace("${date}", format(time, DEFAULT_DATE))
                .replace("${datetime}", format(time, DEFAULT_DATETIME))
                .replace("${year}", String.valueOf(time.getYear()))
                .replace("${month}", pad(time.getMonthValue()))
                .replace("${day}", pad(time.getDayOfMonth()))
                .replace("${hour}", pad(time.getHour()))
                .replace("${minute}", pad(time.getMinute()))
                .replace("${second}", pad(time.getSecond()))
                .replace("${weekday}", WEEKDAYS[time.getDayOfWeek().getValue() - 1]);
        return out;
    }

    /** 逐个替换 {@code ${prefix:<pattern>}} 形式的变量。 */
    private static String replacePattern(String input, String prefix, ZonedDateTime time, String fallback) {
        int from = 0;
        while (true) {
            int start = input.indexOf(prefix, from);
            if (start < 0) return input;
            int end = input.indexOf('}', start + prefix.length());
            if (end < 0) return input; // 缺少右括号，保持原样
            String pattern = input.substring(start + prefix.length(), end).trim();
            String replacement = format(time, pattern.isEmpty() ? fallback : pattern);
            input = input.substring(0, start) + replacement + input.substring(end + 1);
            from = start + replacement.length();
        }
    }

    private static String format(ZonedDateTime time, String pattern) {
        try {
            return time.format(DateTimeFormatter.ofPattern(pattern));
        } catch (IllegalArgumentException ex) {
            return ""; // 配置写错时该变量输出为空，不影响其余文本
        }
    }

    private static String pad(int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }
}

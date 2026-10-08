package com.liu.liuchat.util;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimePlaceholdersTest {

    /** 2025-03-04 13:05:09（周二），亚洲/上海 */
    private static final ZonedDateTime TIME =
            ZonedDateTime.of(2025, 3, 4, 13, 5, 9, 0, ZoneId.of("Asia/Shanghai"));

    @Test void shorthandTimeAndDateVariables() {
        assertEquals("13:05:09", TimePlaceholders.apply("${time}", TIME));
        assertEquals("2025-03-04", TimePlaceholders.apply("${date}", TIME));
        assertEquals("2025-03-04 13:05:09", TimePlaceholders.apply("${datetime}", TIME));
    }

    @Test void yearMonthDayAndClockVariables() {
        assertEquals("2025", TimePlaceholders.apply("${year}", TIME));
        assertEquals("03", TimePlaceholders.apply("${month}", TIME));
        assertEquals("04", TimePlaceholders.apply("${day}", TIME));
        assertEquals("13", TimePlaceholders.apply("${hour}", TIME));
        assertEquals("05", TimePlaceholders.apply("${minute}", TIME));
        assertEquals("09", TimePlaceholders.apply("${second}", TIME));
        assertEquals("周二", TimePlaceholders.apply("${weekday}", TIME));
    }

    @Test void customPatternsAfterColon() {
        assertEquals("13:05", TimePlaceholders.apply("${time:HH:mm}", TIME));
        assertEquals("2025年03月04日", TimePlaceholders.apply("${date:yyyy年MM月dd日}", TIME));
        assertEquals("2025/03/04 13:05:09", TimePlaceholders.apply("${datetime:yyyy/MM/dd HH:mm:ss}", TIME));
        assertEquals("2025-03 周二", TimePlaceholders.apply("${date:yyyy-MM} ${weekday}", TIME));
    }

    @Test void mixedWithOtherPlaceholders() {
        assertEquals("[13:05:09] 2025-03-04 lobby 提醒",
                TimePlaceholders.apply("[${time}] ${date} ${server} 提醒", TIME)
                        .replace("${server}", "lobby"));
    }

    @Test void invalidPatternBecomesEmptyWithoutThrowing() {
        assertEquals("前后", TimePlaceholders.apply("前${time:invalid}后", TIME));
        // 缺少右括号时原样保留，不吞掉后续文本
        assertEquals("前${time:HH:mm 后", TimePlaceholders.apply("前${time:HH:mm 后", TIME));
    }

    @Test void nullEmptyAndNoVariableAreUnchanged() {
        assertEquals(null, TimePlaceholders.apply(null, TIME));
        assertEquals("", TimePlaceholders.apply("", TIME));
        assertEquals("没有变量", TimePlaceholders.apply("没有变量", TIME));
    }
}

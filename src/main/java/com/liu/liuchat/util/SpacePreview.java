package com.liu.liuchat.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.ToLongFunction;

/**
 * 灵魂空间只读预览的纯逻辑：分页、排序与数量展示。
 *
 * <p>54 格 GUI：前 5 行（0~44）放物品，每页 45 格；末行放排序与翻页
 * （{@link #NAV_PREV} 上一页、{@link #NAV_INFO} 页码、{@link #NAV_NEXT} 下一页、
 * {@link #NAV_SORT} 排序切换）。
 */
public final class SpacePreview {
    public static final int PAGE_SIZE = 45;
    public static final int NAV_PREV = 45;
    public static final int NAV_INFO = 47;
    public static final int NAV_NEXT = 49;
    public static final int NAV_SORT = 51;

    private SpacePreview() { }

    /** 带真实数量的展示堆叠：count 用于排序与标注（无限堆叠可远超 64），item 为展示用物品。 */
    public record Counted<T>(T item, long count) { }

    /** 预览排序方式。 */
    public enum Sort {
        COUNT_DESC("数量从多到少"),
        COUNT_ASC("数量从少到多"),
        KEEP("空间原顺序");

        private final String label;

        Sort(String label) { this.label = label; }

        public String label() { return label; }

        /** 切换到下一种排序方式（循环）。 */
        public Sort next() { return values()[(ordinal() + 1) % values().length]; }

        /** 解析配置值：未知值一律按默认的 COUNT_DESC 处理。 */
        public static Sort parse(String value) {
            if (value == null) return COUNT_DESC;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "count-asc", "asc" -> COUNT_ASC;
                case "keep", "none" -> KEEP;
                default -> COUNT_DESC;
            };
        }
    }

    /**
     * 按真实数量排序（稳定排序：数量相同的堆叠保持原相对顺序）。
     * {@link Sort#KEEP} 或不足两项时原样返回；不修改传入列表。
     */
    public static <T> List<T> sort(List<T> items, ToLongFunction<T> count, Sort mode) {
        if (items.size() < 2 || mode == Sort.KEEP) {
            return items;
        }
        List<T> copy = new ArrayList<>(items);
        Comparator<T> cmp = Comparator.comparingLong(count);
        copy.sort(mode == Sort.COUNT_DESC ? cmp.reversed() : cmp);
        return copy;
    }

    /** 总页数：物品为空也至少 1 页。 */
    public static int pageCount(int items) {
        return Math.max(1, (Math.max(0, items) + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    /** 越界页码收敛到 [0, pageCount-1]。 */
    public static int clampPage(int page, int pageCount) {
        return Math.max(0, Math.min(page, Math.max(1, pageCount) - 1));
    }

    /** 当前页起始下标。 */
    public static int from(int page, int items) {
        return clampPage(page, pageCount(items)) * PAGE_SIZE;
    }

    /** 当前页结束下标（不含）。 */
    public static int to(int page, int items) {
        return Math.min(Math.max(0, items), from(page, items) + PAGE_SIZE);
    }

    /** GUI 物品数量展示值：压到原版堆叠上限，真实数量另行标注（灵魂空间无限堆叠可超 64）。 */
    public static int displayAmount(int count) {
        return Math.max(1, Math.min(count, 64));
    }

    /** 真实数量标注。 */
    public static String countLabel(long count) {
        return "§7×" + count;
    }
}

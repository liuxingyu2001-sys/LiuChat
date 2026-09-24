package com.liu.liuchat.util;

/**
 * 灵魂空间只读预览的纯逻辑：分页与数量展示。
 *
 * <p>54 格 GUI：前 5 行（0~44）放物品，每页 45 格；末行放翻页与统计
 * （{@link #NAV_PREV} 上一页、{@link #NAV_INFO} 页码、{@link #NAV_NEXT} 下一页）。
 */
public final class SpacePreview {
    public static final int PAGE_SIZE = 45;
    public static final int NAV_PREV = 45;
    public static final int NAV_INFO = 47;
    public static final int NAV_NEXT = 49;

    private SpacePreview() { }

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

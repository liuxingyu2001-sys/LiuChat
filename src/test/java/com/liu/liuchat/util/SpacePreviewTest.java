package com.liu.liuchat.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpacePreviewTest {

    @Test void pageCountCoversItemsWithAtLeastOnePage() {
        assertEquals(1, SpacePreview.pageCount(0));
        assertEquals(1, SpacePreview.pageCount(1));
        assertEquals(1, SpacePreview.pageCount(45));
        assertEquals(2, SpacePreview.pageCount(46));
        assertEquals(10, SpacePreview.pageCount(450));
        assertEquals(1, SpacePreview.pageCount(-3));
    }

    @Test void clampPageKeepsIndexInsideRange() {
        assertEquals(0, SpacePreview.clampPage(-1, 3));
        assertEquals(2, SpacePreview.clampPage(2, 3));
        assertEquals(2, SpacePreview.clampPage(9, 3));
        assertEquals(0, SpacePreview.clampPage(0, 0));
    }

    @Test void pageSlicesItems() {
        // 50 个堆叠：第 0 页 [0,45)，第 1 页 [45,50)
        assertEquals(0, SpacePreview.from(0, 50));
        assertEquals(45, SpacePreview.to(0, 50));
        assertEquals(45, SpacePreview.from(1, 50));
        assertEquals(50, SpacePreview.to(1, 50));
        // 越界页码收敛到最后一页；空空间无物品
        assertEquals(45, SpacePreview.from(99, 50));
        assertEquals(50, SpacePreview.to(99, 50));
        assertEquals(0, SpacePreview.from(0, 0));
        assertEquals(0, SpacePreview.to(0, 0));
    }

    @Test void displayAmountClampsToStackLimit() {
        assertEquals(1, SpacePreview.displayAmount(0));
        assertEquals(1, SpacePreview.displayAmount(1));
        assertEquals(64, SpacePreview.displayAmount(64));
        assertEquals(64, SpacePreview.displayAmount(65));
        assertEquals(64, SpacePreview.displayAmount(5000));
    }

    @Test void countLabelShowsRealAmount() {
        assertEquals("§7×5000", SpacePreview.countLabel(5000));
        assertEquals("§7×2", SpacePreview.countLabel(2));
    }

    @Test void navSlotsAreDistinctBottomRow() {
        assertTrue(SpacePreview.NAV_PREV > SpacePreview.PAGE_SIZE * 0);
        assertTrue(SpacePreview.NAV_PREV >= SpacePreview.PAGE_SIZE);
        assertTrue(SpacePreview.NAV_PREV < SpacePreview.NAV_INFO);
        assertTrue(SpacePreview.NAV_INFO < SpacePreview.NAV_NEXT);
        assertTrue(SpacePreview.NAV_NEXT < SpacePreview.NAV_SORT);
        assertTrue(SpacePreview.NAV_SORT < 54);
    }

    private static SpacePreview.Counted<String> stack(String name, long count) {
        return new SpacePreview.Counted<>(name, count);
    }

    @Test void sortModeParsesConfigValues() {
        assertEquals(SpacePreview.Sort.COUNT_DESC, SpacePreview.Sort.parse(null));
        assertEquals(SpacePreview.Sort.COUNT_DESC, SpacePreview.Sort.parse(""));
        assertEquals(SpacePreview.Sort.COUNT_DESC, SpacePreview.Sort.parse("count-desc"));
        assertEquals(SpacePreview.Sort.COUNT_ASC, SpacePreview.Sort.parse("count-asc"));
        assertEquals(SpacePreview.Sort.COUNT_ASC, SpacePreview.Sort.parse("ASC"));
        assertEquals(SpacePreview.Sort.KEEP, SpacePreview.Sort.parse("keep"));
        assertEquals(SpacePreview.Sort.COUNT_DESC, SpacePreview.Sort.parse("whatever"));
    }

    @Test void sortModeCyclesAndLabels() {
        assertEquals(SpacePreview.Sort.COUNT_ASC, SpacePreview.Sort.COUNT_DESC.next());
        assertEquals(SpacePreview.Sort.KEEP, SpacePreview.Sort.COUNT_ASC.next());
        assertEquals(SpacePreview.Sort.COUNT_DESC, SpacePreview.Sort.KEEP.next());
        assertEquals("数量从多到少", SpacePreview.Sort.COUNT_DESC.label());
    }

    @Test void defaultSortsByCountDescendingStably() {
        List<SpacePreview.Counted<String>> items =
                List.of(stack("a", 5), stack("b", 9), stack("c", 5), stack("d", 1));
        List<SpacePreview.Counted<String>> sorted =
                SpacePreview.sort(items, SpacePreview.Counted::count, SpacePreview.Sort.COUNT_DESC);
        // 数量相同（a、c 各 5）保持原相对顺序
        assertEquals(List.of("b", "a", "c", "d"),
                sorted.stream().map(SpacePreview.Counted::item).toList());
    }

    @Test void ascendingSortAndKeepMode() {
        List<SpacePreview.Counted<String>> items =
                List.of(stack("a", 5), stack("b", 9), stack("d", 1));
        assertEquals(List.of("d", "a", "b"),
                SpacePreview.sort(items, SpacePreview.Counted::count, SpacePreview.Sort.COUNT_ASC)
                        .stream().map(SpacePreview.Counted::item).toList());
        assertEquals(List.of("a", "b", "d"),
                SpacePreview.sort(items, SpacePreview.Counted::count, SpacePreview.Sort.KEEP)
                        .stream().map(SpacePreview.Counted::item).toList());
    }

    @Test void sortDoesNotMutateSource() {
        List<SpacePreview.Counted<String>> items =
                new ArrayList<>(List.of(stack("a", 5), stack("b", 9)));
        SpacePreview.sort(items, SpacePreview.Counted::count, SpacePreview.Sort.COUNT_DESC);
        assertEquals("a", items.get(0).item());
    }
}

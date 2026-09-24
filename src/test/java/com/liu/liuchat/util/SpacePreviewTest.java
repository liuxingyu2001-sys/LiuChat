package com.liu.liuchat.util;

import org.junit.jupiter.api.Test;

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
        assertTrue(SpacePreview.NAV_NEXT < 54);
    }
}

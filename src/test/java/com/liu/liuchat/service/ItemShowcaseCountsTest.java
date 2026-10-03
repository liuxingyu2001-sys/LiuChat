package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemShowcaseCountsTest {

    @Test void truncationDropsFromTheTailSoEarlierTokensKeepTheirChips() {
        // token0 展示 4 件、token1 展示 1 件，预算只够 3 件 → 从最后一个 token 往回扣
        assertArrayEquals(new int[]{3, 0}, ItemShowcase.truncate(List.of(4, 1), 3));
        // 尾部只有 1 件可扣，不够就继续往前一个 token 扣
        assertArrayEquals(new int[]{2, 0}, ItemShowcase.truncate(List.of(3, 1), 2));
        assertArrayEquals(new int[]{1, 0}, ItemShowcase.truncate(List.of(3, 1), 1));
    }

    @Test void truncationKeepsCountsWhenEverythingFits() {
        assertArrayEquals(new int[]{2, 1}, ItemShowcase.truncate(List.of(2, 1), 3));
        assertArrayEquals(new int[]{0, 4}, ItemShowcase.truncate(List.of(0, 4), 4));
    }

    @Test void truncationNeverGoesNegativeOnMalformedCounts() {
        assertArrayEquals(new int[]{0, 0}, ItemShowcase.truncate(List.of(-5, 0), 3));
    }

    @Test void compositeIdCarriesTheStackIndex() {
        assertEquals("abc-123#2", ItemShowcase.subId("abc-123", 2));
        assertEquals("abc-123", ItemShowcase.baseId("abc-123"));
        assertEquals(0, ItemShowcase.stackIndex("abc-123"));
        assertEquals("abc-123", ItemShowcase.baseId("abc-123#3"));
        assertEquals(3, ItemShowcase.stackIndex("abc-123#3"));
        // 非法序号一律回落到第 0 件，避免点开越界物品
        assertEquals("abc", ItemShowcase.baseId("abc#oops"));
        assertEquals(0, ItemShowcase.stackIndex("abc#oops"));
        assertEquals(0, ItemShowcase.stackIndex(null));
    }
}

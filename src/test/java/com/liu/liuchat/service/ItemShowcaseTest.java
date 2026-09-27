package com.liu.liuchat.service;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemShowcaseTest {

    @Test void detectsShulkerBoxes() {
        assertTrue(ItemShowcase.isShulkerBox(Material.SHULKER_BOX));
        assertTrue(ItemShowcase.isShulkerBox(Material.WHITE_SHULKER_BOX));
        assertTrue(ItemShowcase.isShulkerBox(Material.PURPLE_SHULKER_BOX));
        assertTrue(ItemShowcase.isShulkerBox(Material.RED_SHULKER_BOX));
    }

    @Test void ignoresOtherItems() {
        assertFalse(ItemShowcase.isShulkerBox(Material.SHULKER_SHELL));
        assertFalse(ItemShowcase.isShulkerBox(Material.CHEST));
        assertFalse(ItemShowcase.isShulkerBox(Material.BARREL));
        assertFalse(ItemShowcase.isShulkerBox(Material.DIAMOND));
        assertFalse(ItemShowcase.isShulkerBox(null));
    }

    @Test void detectsVanillaBundles() {
        assertTrue(ItemShowcase.isBundle(Material.BUNDLE));
        assertTrue(ItemShowcase.isBundle(Material.WHITE_BUNDLE));
        assertTrue(ItemShowcase.isBundle(Material.RED_BUNDLE));
        assertFalse(ItemShowcase.isBundle(Material.SHULKER_BOX));
        assertFalse(ItemShowcase.isBundle(Material.DIAMOND));
        assertFalse(ItemShowcase.isBundle(null));
    }

    @Test void bundlePreviewPageBoundaries() {
        assertTrue(com.liu.liuchat.util.SpacePreview.pageCount(0) == 1);
        assertTrue(com.liu.liuchat.util.SpacePreview.pageCount(45) == 1);
        assertTrue(com.liu.liuchat.util.SpacePreview.pageCount(46) == 2);
        assertTrue(com.liu.liuchat.util.SpacePreview.from(1, 46) == 45);
        assertTrue(com.liu.liuchat.util.SpacePreview.to(1, 46) == 46);
    }

    @Test void soulSpaceRingCheckRejectsBlankInput() {
        assertFalse(ItemShowcase.isSoulSpaceRing(null, "soulspace:ring"));
        assertFalse(ItemShowcase.isSoulSpaceRing(null, null));
        assertFalse(ItemShowcase.isSoulSpaceRing(null, ""));
        assertFalse(ItemShowcase.isSoulSpaceRing(null, "not a key"));
    }
}

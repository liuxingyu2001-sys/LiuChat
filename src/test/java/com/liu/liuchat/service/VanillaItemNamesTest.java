package com.liu.liuchat.service;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VanillaItemNamesTest {
    @Test void resolvesVanillaDiamondFromBundledChineseFile() {
        assertEquals("钻石", new VanillaItemNames().resolve(Material.DIAMOND));
    }
}

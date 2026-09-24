package com.liu.liuchat.service;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemShowcaseNameTest {
    private final CraftEngineNames names = new CraftEngineNames(Map.of("item.example", "资源包物品名"));

    @Test void anvilCustomNameWinsOverItemName() {
        ItemMeta meta = meta(Component.text("铁砧改名"), Component.translatable("item.example"));
        assertEquals("铁砧改名", ItemShowcase.preferredName(meta, names, "钻石剑"));
    }

    @Test void fallsBackToItemNameThenVanillaName() {
        assertEquals("资源包物品名", ItemShowcase.preferredName(
                meta(null, Component.translatable("item.example")), names, "钻石剑"));
        assertEquals("钻石剑", ItemShowcase.preferredName(meta(null, null), names, "钻石剑"));
    }

    private static ItemMeta meta(Component custom, Component itemName) {
        return (ItemMeta) Proxy.newProxyInstance(ItemMeta.class.getClassLoader(), new Class[]{ItemMeta.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasDisplayName" -> custom != null;
                    case "displayName" -> custom;
                    case "hasItemName" -> itemName != null;
                    case "itemName" -> itemName;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}

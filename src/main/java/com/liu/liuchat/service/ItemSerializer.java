package com.liu.liuchat.service;

import org.bukkit.inventory.ItemStack;

import java.util.Base64;

/** 邮件附件使用的无 YAML 序列化：Paper 的二进制 ItemStack 格式，避免反序列化任意配置类。 */
public final class ItemSerializer {
    private ItemSerializer() {
    }

    /** @return 二进制序列化结果；空气/空物品返回 null */
    public static byte[] bytes(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        return item.clone().serializeAsBytes();
    }

    public static String encode(byte[] bytes) {
        return bytes == null ? "" : Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }

    public static ItemStack decode(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        try {
            ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
            return item == null || item.getType().isAir() ? null : item;
        } catch (RuntimeException error) {
            return null;
        }
    }
}

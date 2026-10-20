package com.liu.liuchat.service;

import com.liuxingyu.liulib.LiuLib;
import org.bukkit.inventory.ItemStack;

import java.util.Base64;

/**
 * 邮件附件序列化。
 *
 * <p>实际编解码委托 LiuLib 的共享 {@code ItemCodec}：同样是 Paper 二进制 NBT，
 * 并兼容旧版 Java 对象流；LiuChat 不再维护第三份物品序列化实现。</p>
 */
public final class ItemSerializer {
    private ItemSerializer() {
    }

    /** @return 二进制序列化结果；空气/空物品返回 null */
    public static byte[] bytes(ItemStack item) {
        return LiuLib.items().codec().encodeBytes(item);
    }

    public static String encode(byte[] bytes) {
        return bytes == null ? "" : Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }

    public static ItemStack decode(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        try {
            ItemStack item = LiuLib.items().codec().decodeBytes(Base64.getDecoder().decode(encoded));
            return item == null || item.getType().isAir() ? null : item;
        } catch (RuntimeException error) {
            // 旧邮件可能包含损坏/旧格式的 Base64；不能让一封坏邮件拖垮整个列表。
            return null;
        }
    }
}

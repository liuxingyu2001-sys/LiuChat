package com.liu.liuchat.service;

import com.liu.liuchat.config.ConfigManager;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 邮件附件黑名单：默认禁止邮寄“自定义物品”，避免管理员物品、活动道具、
 * 带插件数据的道具通过邮件跨服转移。
 *
 * <p>判定顺序：黑名单材质 → 自定义名称 → 自定义 Lore → CustomModelData →
 * 任意 PDC/NBT 数据。带 PDC 的物品只有在其所有 key 都命中
 * {@code allow-persistent-keys}（支持 {@code plugin:*} 通配）时才放行。</p>
 *
 * <p>规则同时用于 GUI（发送前提示具体原因）和 {@code MailService}
 * （API 调用方得到 {@link com.liu.liuchat.api.MailResult#ATTACHMENT_BLOCKED}），
 * 两道都拦，避免绕过界面直接调 API。</p>
 */
public final class MailAttachmentFilter {

    /** 拦截原因，对应 {@code mail.attach-blocked-<suffix>} 消息。 */
    public enum Reason {
        MATERIAL("material"),
        CUSTOM_NAME("name"),
        CUSTOM_LORE("lore"),
        CUSTOM_MODEL_DATA("model-data"),
        PERSISTENT_DATA("pdc"),
        /** 物品名/Lore/PDC key 命中绑定、租赁等关键字。 */
        KEYWORD("keyword");

        private final String suffix;

        Reason(String suffix) {
            this.suffix = suffix;
        }

        public String messageKey() {
            return "mail.attach-blocked-" + suffix;
        }
    }

    /**
     * 默认拦截的 PDC 数据 / 名称 / Lore 关键字（小写）。插件封装的“绑定/租赁”物品
     * 各自用不同的 key 或文案标记，这里统一按关键字兜底，避免出现在默认白名单外。
     */
    public static final List<String> DEFAULT_BLOCKED_KEYWORDS = List.of(
            "soulbound", "bound_id", "rentplus", "rent_id", "租赁", "绑定", "专属");

    private MailAttachmentFilter() {
    }

    /** @return null 表示允许发送，否则为第一个命中的拦截原因 */
    public static Reason check(ItemStack item, ConfigManager config) {
        if (item == null || item.getType().isAir() || config == null) return null;
        ItemMeta meta = item.getItemMeta();
        boolean customName = false;
        boolean customLore = false;
        boolean customModelData = false;
        boolean blockedKeyword = false;
        Set<String> pdcKeys = Set.of();
        if (meta != null) {
            customName = meta.hasDisplayName();
            List<Component> lore = meta.lore();
            customLore = lore != null && !lore.isEmpty();
            customModelData = meta.hasCustomModelData();
            pdcKeys = meta.getPersistentDataContainer().getKeys().stream()
                    .map(NamespacedKey::toString).collect(java.util.stream.Collectors.toSet());
            List<String> texts = new ArrayList<>();
            if (customName) texts.add(plain(meta.displayName()));
            if (customLore) lore.forEach(line -> texts.add(plain(line)));
            blockedKeyword = matchesBlockedKeyword(pdcKeys) || matchesBlockedKeyword(texts);
        }
        return check(config.mailBlockedMaterials().contains(item.getType()),
                customName && config.mailBlockCustomName(),
                customLore && config.mailBlockCustomLore(),
                customModelData && config.mailBlockCustomModelData(),
                config.mailBlockPersistentData() ? pdcKeys : Set.of(),
                config.mailAllowedPersistentKeys(),
                blockedKeyword);
    }

    private static String plain(Component component) {
        return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
    }

    /**
     * 关键字兜底：任意 PDC key、物品名或 Lore 里出现绑定/租赁类标记就视为不可邮寄。
     * 覆盖各插件自定义的物品（灵魂绑定、租赁专用等），不依赖它们的命名空间。
     */
    static boolean matchesBlockedKeyword(java.util.Collection<String> values) {
        return matchesBlockedKeyword(values, DEFAULT_BLOCKED_KEYWORDS);
    }

    static boolean matchesBlockedKeyword(java.util.Collection<String> values, List<String> keywords) {
        if (values == null || values.isEmpty() || keywords == null || keywords.isEmpty()) return false;
        for (String value : values) {
            if (value == null) continue;
            String normalized = value.toLowerCase(Locale.ROOT);
            for (String keyword : keywords) {
                if (!keyword.isEmpty() && normalized.contains(keyword)) return true;
            }
        }
        return false;
    }

    /**
     * 纯判定逻辑（不依赖服务端），便于单元测试：{@code pdcKeys} 里只要有一个 key
     * 不在允许列表中，就判定为自定义物品。
     */
    static Reason check(boolean blockedMaterial, boolean customName, boolean customLore,
                        boolean customModelData, Set<String> pdcKeys, Set<String> allowedKeys) {
        return check(blockedMaterial, customName, customLore, customModelData, pdcKeys, allowedKeys, false);
    }

    static Reason check(boolean blockedMaterial, boolean customName, boolean customLore,
                        boolean customModelData, Set<String> pdcKeys, Set<String> allowedKeys,
                        boolean blockedKeyword) {
        if (blockedMaterial) return Reason.MATERIAL;
        if (blockedKeyword) return Reason.KEYWORD;
        if (customName) return Reason.CUSTOM_NAME;
        if (customLore) return Reason.CUSTOM_LORE;
        if (customModelData) return Reason.CUSTOM_MODEL_DATA;
        for (String key : pdcKeys) {
            if (!allowedKey(key, allowedKeys)) return Reason.PERSISTENT_DATA;
        }
        return null;
    }

    /** PDC key 是否命中允许列表；{@code myplugin:*} 放行该命名空间下所有 key。 */
    public static boolean allowedKey(String key, Set<String> allowed) {
        if (key == null || allowed == null || allowed.isEmpty()) return false;
        String normalized = key.toLowerCase(Locale.ROOT);
        if (allowed.contains(normalized)) return true;
        int colon = normalized.indexOf(':');
        return colon > 0 && allowed.contains(normalized.substring(0, colon) + ":*");
    }
}

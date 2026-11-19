package com.liu.liuchat.service;

import com.liu.liuchat.config.ConfigManager;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 邮件附件过滤：只保留黑名单模式 —— 只拦截命中黑名单的物品，普通物品（含改名、Lore、
 * 插件 PDC 数据）都可以正常邮寄。
 *
 * <p>配置 {@code mail.attachment-filter} 下只有三组黑名单规则：</p>
 * <ul>
 *   <li>{@code materials}：按材质拦截；</li>
 *   <li>{@code blocked-persistent-keys}：按 PDC key 拦截（支持 {@code plugin:*} 通配）；</li>
 *   <li>{@code blocked-keywords}：物品名 / Lore / PDC key 命中绑定、租赁等关键字即拦截。</li>
 * </ul>
 *
 * <p>判定顺序：材质 → 关键字 → 被拉黑的 PDC key。规则同时用于 GUI（发送前提示具体原因）
 * 和 {@code MailService}（API 调用方得到 {@link com.liu.liuchat.api.MailResult#ATTACHMENT_BLOCKED}），
 * 两道都拦，避免绕过界面直接调 API。{@code attachment-filter.enable: false} 时本类直接放行。</p>
 */
public final class MailAttachmentFilter {

    /** 拦截原因，对应 {@code mail.attach-blocked-<suffix>} 消息。 */
    public enum Reason {
        MATERIAL("material"),
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
     * 内置默认拦截关键字（小写）。内置 {@code config.yml} 的 {@code blocked-keywords}
     * 与这里保持一致（有单元测试守住），用于灵魂绑定、租赁、专属类物品兜底。
     */
    public static final List<String> DEFAULT_BLOCKED_KEYWORDS = List.of(
            "soulbound", "bound_id", "rentplus", "rent_id", "租赁", "绑定", "专属");

    private MailAttachmentFilter() {
    }

    /** @return null 表示允许发送，否则为第一个命中的拦截原因 */
    public static Reason check(ItemStack item, ConfigManager config) {
        if (item == null || item.getType().isAir() || config == null) return null;
        if (!config.mailAttachmentFilterEnabled()) return null;
        ItemMeta meta = item.getItemMeta();
        Set<String> pdcKeys = Set.of();
        List<String> texts = new ArrayList<>();
        if (meta != null) {
            pdcKeys = meta.getPersistentDataContainer().getKeys().stream()
                    .map(NamespacedKey::toString).collect(java.util.stream.Collectors.toSet());
            if (meta.hasDisplayName()) texts.add(plain(meta.displayName()));
            List<Component> lore = meta.lore();
            if (lore != null) lore.forEach(line -> texts.add(plain(line)));
        }
        List<String> keywords = config.mailBlockedKeywords();
        boolean blockedKeyword = matchesBlockedKeyword(pdcKeys, keywords)
                || matchesBlockedKeyword(texts, keywords);
        return checkBlacklist(config.mailBlockedMaterials().contains(item.getType()), pdcKeys,
                config.mailBlockedPersistentKeys(), blockedKeyword);
    }

    private static String plain(Component component) {
        return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
    }

    /**
     * 关键字兜底：任意 PDC key、物品名或 Lore 里出现绑定/租赁/专属类标记就视为不可邮寄。
     * 覆盖各插件自定义的物品（灵魂绑定、租赁专用等），不依赖它们的命名空间。
     */
    static boolean matchesBlockedKeyword(Collection<String> values) {
        return matchesBlockedKeyword(values, DEFAULT_BLOCKED_KEYWORDS);
    }

    static boolean matchesBlockedKeyword(Collection<String> values, List<String> keywords) {
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
     * 黑名单纯判定（不依赖服务端）：材质 → 关键字 → 被拉黑的 PDC key，
     * 其余自定义名称/Lore/PDC 一律放行。
     */
    static Reason checkBlacklist(boolean blockedMaterial, Set<String> pdcKeys,
                                 Set<String> blockedKeys, boolean blockedKeyword) {
        if (blockedMaterial) return Reason.MATERIAL;
        if (blockedKeyword) return Reason.KEYWORD;
        for (String key : pdcKeys) {
            if (matchedKey(key, blockedKeys)) return Reason.PERSISTENT_DATA;
        }
        return null;
    }

    /** PDC key 是否命中黑名单；{@code myplugin:*} 拦截该命名空间下所有 key。 */
    public static boolean blockedKey(String key, Set<String> blocked) {
        return matchedKey(key, blocked);
    }

    /** key 与黑名单列表匹配：精确命中，或列表里的 {@code plugin:*} 通配整个命名空间。 */
    static boolean matchedKey(String key, Set<String> patterns) {
        if (key == null || patterns == null || patterns.isEmpty()) return false;
        String normalized = key.toLowerCase(Locale.ROOT);
        if (patterns.contains(normalized)) return true;
        int colon = normalized.indexOf(':');
        return colon > 0 && patterns.contains(normalized.substring(0, colon) + ":*");
    }
}

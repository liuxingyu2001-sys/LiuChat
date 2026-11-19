package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 邮件附件过滤：只保留黑名单模式，命中材质 / PDC key / 关键字才拦截。 */
class MailAttachmentFilterTest {

    @Test
    void allowsPlainCustomItems() {
        // 普通物品、改名、Lore、未知 PDC 都不应被拦截
        assertNull(MailAttachmentFilter.checkBlacklist(false, Set.of(), Set.of(), false));
        assertNull(MailAttachmentFilter.checkBlacklist(false, Set.of("other:key"), Set.of(), false));
        assertNull(MailAttachmentFilter.checkBlacklist(false, Set.of("other:key"), Set.of("rentplus:*"), false));
    }

    @Test
    void blocksListedMaterial() {
        assertEquals(MailAttachmentFilter.Reason.MATERIAL,
                MailAttachmentFilter.checkBlacklist(true, Set.of(), Set.of(), false));
    }

    @Test
    void blocksConfiguredPersistentKeys() {
        // 精确 key 与命名空间通配都生效
        assertEquals(MailAttachmentFilter.Reason.PERSISTENT_DATA,
                MailAttachmentFilter.checkBlacklist(false, Set.of("rentplus:rent_id"), Set.of("rentplus:rent_id"), false));
        assertEquals(MailAttachmentFilter.Reason.PERSISTENT_DATA,
                MailAttachmentFilter.checkBlacklist(false, Set.of("rentplus:anything"), Set.of("rentplus:*"), false));
        assertNull(MailAttachmentFilter.checkBlacklist(false, Set.of("rentplus:anything"), Set.of("other:*"), false));
    }

    @Test
    void keywordWinsOverUnknownPersistentData() {
        assertEquals(MailAttachmentFilter.Reason.KEYWORD,
                MailAttachmentFilter.checkBlacklist(false, Set.of("some:key"), Set.of(), true));
    }

    @Test
    void keywordCanBeDisabledWithEmptyList() {
        assertFalse(MailAttachmentFilter.matchesBlockedKeyword(List.of("soulbound 灵魂绑定"), List.of()));
        assertNull(MailAttachmentFilter.checkBlacklist(false, Set.of("soulbound:tag"), Set.of(), false));
    }

    @Test
    void defaultKeywordsCoverSoulboundAndRentItems() {
        for (String keyword : MailAttachmentFilter.DEFAULT_BLOCKED_KEYWORDS) {
            assertTrue(MailAttachmentFilter.matchesBlockedKeyword(List.of("§7" + keyword)),
                    "keyword should match: " + keyword);
        }
        assertTrue(MailAttachmentFilter.matchesBlockedKeyword(List.of("RentPlus:item:3")));
        assertTrue(MailAttachmentFilter.matchesBlockedKeyword(List.of("这部坐骑是专属的")));
        assertFalse(MailAttachmentFilter.matchesBlockedKeyword(List.of("minecraft:custom_name")));
    }

    @Test
    void onlyBlacklistReasonsAndMessageKeysRemain() {
        // 旧 whitelist 模式的自定义名称/Lore/模型数据原因已移除
        assertEquals(Set.of(MailAttachmentFilter.Reason.MATERIAL,
                        MailAttachmentFilter.Reason.PERSISTENT_DATA,
                        MailAttachmentFilter.Reason.KEYWORD),
                Set.of(MailAttachmentFilter.Reason.values()));
        for (MailAttachmentFilter.Reason reason : MailAttachmentFilter.Reason.values()) {
            assertTrue(reason.messageKey().startsWith("mail.attach-blocked"),
                    "unexpected key: " + reason.messageKey());
        }
    }

    @Test
    void namespaceWildcardOnlyMatchesItsOwnNamespace() {
        assertTrue(MailAttachmentFilter.blockedKey("rentplus:rent_id", Set.of("rentplus:*")));
        assertTrue(MailAttachmentFilter.blockedKey("LiUChat:Tag", Set.of("liuchat:tag")));
        assertFalse(MailAttachmentFilter.blockedKey("other:tag", Set.of("rentplus:*")));
        assertFalse(MailAttachmentFilter.blockedKey("liuchat:tag", Set.of()));
    }
}

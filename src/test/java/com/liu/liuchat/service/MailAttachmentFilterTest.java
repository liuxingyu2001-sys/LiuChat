package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 邮件附件过滤：默认黑名单只拦命中项，whitelist 模式保留“只放行普通物品”的旧行为。 */
class MailAttachmentFilterTest {

    // ---------------- blacklist 模式（默认） ----------------

    @Test
    void blacklistAllowsPlainCustomItems() {
        // 普通物品、改名、Lore、未知 PDC 都不应被拦截
        assertNull(MailAttachmentFilter.checkBlacklist(false, Set.of(), Set.of(), false));
        assertNull(MailAttachmentFilter.checkBlacklist(false, Set.of("other:key"), Set.of(), false));
        assertNull(MailAttachmentFilter.checkBlacklist(false, Set.of("other:key"), Set.of("rentplus:*"), false));
    }

    @Test
    void blacklistBlocksListedMaterial() {
        assertEquals(MailAttachmentFilter.Reason.MATERIAL,
                MailAttachmentFilter.checkBlacklist(true, Set.of(), Set.of(), false));
    }

    @Test
    void blacklistBlocksConfiguredPersistentKeys() {
        // 精确 key 与命名空间通配都生效
        assertEquals(MailAttachmentFilter.Reason.PERSISTENT_DATA,
                MailAttachmentFilter.checkBlacklist(false, Set.of("rentplus:rent_id"), Set.of("rentplus:rent_id"), false));
        assertEquals(MailAttachmentFilter.Reason.PERSISTENT_DATA,
                MailAttachmentFilter.checkBlacklist(false, Set.of("rentplus:anything"), Set.of("rentplus:*"), false));
        assertNull(MailAttachmentFilter.checkBlacklist(false, Set.of("rentplus:anything"), Set.of("other:*"), false));
    }

    @Test
    void blacklistKeywordWinsOverUnknownPersistentData() {
        assertEquals(MailAttachmentFilter.Reason.KEYWORD,
                MailAttachmentFilter.checkBlacklist(false, Set.of("some:key"), Set.of(), true));
    }

    @Test
    void blacklistKeywordCanBeDisabledWithEmptyList() {
        assertFalse(MailAttachmentFilter.matchesBlockedKeyword(List.of("soulbound 灵魂绑定"), List.of()));
        assertNull(MailAttachmentFilter.checkBlacklist(false, Set.of("soulbound:tag"), Set.of(), false));
    }

    // ---------------- whitelist 模式（旧行为） ----------------

    private static MailAttachmentFilter.Reason whitelist(boolean material, boolean name, boolean lore,
                                                         boolean model, Set<String> pdc, Set<String> allowed) {
        return MailAttachmentFilter.checkWhitelist(material, name, lore, model, pdc, allowed, false);
    }

    @Test
    void whitelistAllowsPlainItem() {
        assertNull(whitelist(false, false, false, false, Set.of(), Set.of()));
    }

    @Test
    void whitelistBlockedMaterialWins() {
        assertEquals(MailAttachmentFilter.Reason.MATERIAL,
                whitelist(true, true, true, true, Set.of("x:y"), Set.of()));
    }

    @Test
    void whitelistBlocksCustomNameLoreAndModelData() {
        assertEquals(MailAttachmentFilter.Reason.CUSTOM_NAME,
                whitelist(false, true, false, false, Set.of(), Set.of()));
        assertEquals(MailAttachmentFilter.Reason.CUSTOM_LORE,
                whitelist(false, false, true, false, Set.of(), Set.of()));
        assertEquals(MailAttachmentFilter.Reason.CUSTOM_MODEL_DATA,
                whitelist(false, false, false, true, Set.of(), Set.of()));
    }

    @Test
    void whitelistPersistentDataIsBlockedUnlessAllowed() {
        assertEquals(MailAttachmentFilter.Reason.PERSISTENT_DATA,
                whitelist(false, false, false, false, Set.of("mythicmobs:item"), Set.of()));
        assertEquals(MailAttachmentFilter.Reason.PERSISTENT_DATA,
                whitelist(false, false, false, false, Set.of("other:key"), Set.of("mythicmobs:*")));
        assertNull(whitelist(false, false, false, false, Set.of("mythicmobs:item"), Set.of("mythicmobs:*")));
    }

    // ---------------- 关键字与 key 匹配 ----------------

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
    void everyBlockedReasonHasAMessageKey() {
        for (MailAttachmentFilter.Reason reason : MailAttachmentFilter.Reason.values()) {
            assertTrue(reason.messageKey().startsWith("mail.attach-blocked"),
                    "unexpected key: " + reason.messageKey());
        }
    }

    @Test
    void namespaceWildcardOnlyMatchesItsOwnNamespace() {
        assertTrue(MailAttachmentFilter.allowedKey("liuchat:tag", Set.of("liuchat:*")));
        assertTrue(MailAttachmentFilter.allowedKey("LiUChat:Tag", Set.of("liuchat:tag")));
        assertFalse(MailAttachmentFilter.allowedKey("other:tag", Set.of()));
        assertTrue(MailAttachmentFilter.blockedKey("rentplus:rent_id", Set.of("rentplus:*")));
        assertFalse(MailAttachmentFilter.blockedKey("other:tag", Set.of("rentplus:*")));
    }

    @Test
    void modeFallsBackToBlacklistForUnknownValues() {
        assertEquals(MailAttachmentFilter.Mode.WHITELIST, MailAttachmentFilter.Mode.parse(" WHITELIST "));
        assertEquals(MailAttachmentFilter.Mode.BLACKLIST, MailAttachmentFilter.Mode.parse("blacklist"));
        assertEquals(MailAttachmentFilter.Mode.BLACKLIST, MailAttachmentFilter.Mode.parse(""));
        assertEquals(MailAttachmentFilter.Mode.BLACKLIST, MailAttachmentFilter.Mode.parse(null));
        assertEquals(MailAttachmentFilter.Mode.BLACKLIST, MailAttachmentFilter.Mode.parse("typo"));
    }
}

package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 邮件附件黑名单：默认拦截自定义名称/Lore/模型数据/PDC，纯逻辑无需服务端。 */
class MailAttachmentFilterTest {

    private static MailAttachmentFilter.Reason check(boolean material, boolean name, boolean lore,
                                                     boolean model, Set<String> pdc, Set<String> allowed) {
        return MailAttachmentFilter.check(material, name, lore, model, pdc, allowed);
    }

    @Test
    void plainItemIsAllowed() {
        assertNull(check(false, false, false, false, Set.of(), Set.of()));
    }

    @Test
    void blockedMaterialWins() {
        assertEquals(MailAttachmentFilter.Reason.MATERIAL,
                check(true, true, true, true, Set.of("x:y"), Set.of()));
    }

    @Test
    void customNameLoreAndModelDataAreBlocked() {
        assertEquals(MailAttachmentFilter.Reason.CUSTOM_NAME,
                check(false, true, false, false, Set.of(), Set.of()));
        assertEquals(MailAttachmentFilter.Reason.CUSTOM_LORE,
                check(false, false, true, false, Set.of(), Set.of()));
        assertEquals(MailAttachmentFilter.Reason.CUSTOM_MODEL_DATA,
                check(false, false, false, true, Set.of(), Set.of()));
    }

    @Test
    void persistentDataIsBlockedUnlessWhitelisted() {
        assertEquals(MailAttachmentFilter.Reason.PERSISTENT_DATA,
                check(false, false, false, false, Set.of("mythicmobs:item"), Set.of()));
        assertEquals(MailAttachmentFilter.Reason.PERSISTENT_DATA,
                check(false, false, false, false, Set.of("other:key"), Set.of("mythicmobs:*")));
        assertNull(check(false, false, false, false, Set.of("mythicmobs:item"), Set.of("mythicmobs:*")));
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
    void keywordBeatsCustomNameAndLore() {
        assertEquals(MailAttachmentFilter.Reason.KEYWORD,
                MailAttachmentFilter.check(false, true, true, false,
                        Set.of(), Set.of(), true));
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
    }
}

package com.liu.liuchat.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外部插件（如工会插件）可以把任意展示名当邮件收件人/发件人写入，
 * 旧库 VARCHAR(32) 曾因此抛 {@code Data too long for column 'recipient_name'}。
 * 写入前必须按列宽截断，且不能把 emoji 的代理对劈开。
 */
class MailNameClampTest {

    @Test
    void nullBecomesEmpty() {
        assertEquals("", AbstractJdbcDatabase.clampName(null));
    }

    @Test
    void shortNameUnchanged() {
        assertEquals("Alex", AbstractJdbcDatabase.clampName("Alex"));
    }

    @Test
    void nameAtLimitUnchanged() {
        String name = "a".repeat(AbstractJdbcDatabase.MAIL_NAME_MAX);
        assertEquals(name, AbstractJdbcDatabase.clampName(name));
    }

    @Test
    void longAsciiNameTruncated() {
        String name = "a".repeat(AbstractJdbcDatabase.MAIL_NAME_MAX + 10);
        assertEquals(AbstractJdbcDatabase.MAIL_NAME_MAX,
                AbstractJdbcDatabase.clampName(name).length());
    }

    @Test
    void legacyWidthTruncationFitsOldColumn() {
        String name = "a".repeat(100);
        assertEquals(AbstractJdbcDatabase.MAIL_NAME_LEGACY_MAX,
                AbstractJdbcDatabase.clampName(name, AbstractJdbcDatabase.MAIL_NAME_LEGACY_MAX).length());
    }

    @Test
    void detectsDataTooLongError() {
        assertTrue(AbstractJdbcDatabase.isNameTooLong(new java.sql.SQLException(
                "Data too long for column 'recipient_name' at row 1", "22001")));
        assertTrue(AbstractJdbcDatabase.isNameTooLong(new java.sql.SQLException(
                "Data truncation: Data too long for column 'sender_name' at row 1")));
        assertFalse(AbstractJdbcDatabase.isNameTooLong(new java.sql.SQLException(
                "Duplicate entry 'x' for key 'PRIMARY'", "23000")));
    }

    @Test
    void emojiNameKeepsWholeCodePoints() {
        String name = "😀".repeat(AbstractJdbcDatabase.MAIL_NAME_MAX + 5);
        String clamped = AbstractJdbcDatabase.clampName(name);
        assertFalse(Character.isHighSurrogate(clamped.charAt(clamped.length() - 1)));
        assertEquals(AbstractJdbcDatabase.MAIL_NAME_MAX,
                clamped.codePointCount(0, clamped.length()));
        assertEquals(AbstractJdbcDatabase.MAIL_NAME_MAX * 2, clamped.length());
    }
}

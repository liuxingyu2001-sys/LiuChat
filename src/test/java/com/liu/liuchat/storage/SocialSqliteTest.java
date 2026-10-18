package com.liu.liuchat.storage;

import com.liu.liuchat.model.FriendRequestRow;
import com.liu.liuchat.model.FriendRow;
import com.liu.liuchat.model.MailRow;
import com.liu.liuchat.model.PlayerIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SQLite 社交表建表/读写回归；使用临时目录，不触碰真实服务器数据。 */
class SocialSqliteTest {
    private static final Logger LOGGER = Logger.getLogger("SocialSqliteTest." + System.nanoTime());

    static {
        LOGGER.setUseParentHandlers(false);
        LOGGER.setLevel(Level.OFF);
    }

    /** 只借用 JavaPlugin 的 dataFolder/logger，不启动 Bukkit 服务端。 */
    private static SqliteDatabase open(Path dir) {
        SqliteDatabase database = new SqliteDatabase(dir.resolve("liuchat.db").toFile(), LOGGER);
        assertTrue(database.init(), "SQLite 应初始化成功");
        return database;
    }

    @Test
    void identityFriendAndRequestRoundTrip(@TempDir Path dir) {
        SqliteDatabase db = open(dir);
        try {
            db.saveIdentity(new PlayerIdentity("u-a", "Alice", 10));
            db.saveIdentity(new PlayerIdentity("u-b", "Bob", 20));
            assertEquals("Alice", db.loadIdentityByName("alice").name());
            assertEquals("Bob", db.loadIdentityByUuid("u-b").name());
            assertNull(db.loadIdentityByName("nobody"));

            FriendRequestRow request = new FriendRequestRow("u-b", "Bob", "u-a", "Alice", 200, Long.MAX_VALUE);
            assertTrue(db.addFriendRequest(request));
            assertFalse(db.addFriendRequest(request), "重复请求应幂等");
            assertNotNull(db.loadFriendRequest("u-b", "u-a"));
            assertEquals(1, db.loadIncomingRequests("u-a", 300).size());
            assertEquals(1, db.loadOutgoingRequests("u-b", 300).size());
            assertEquals(1, db.removeFriendRequestsBetween("u-a", "u-b"));

            assertTrue(db.addFriend("u-a", "Alice", "u-b", "Bob", 100));
            assertFalse(db.addFriend("u-a", "Alice", "u-b", "Bob", 100), "重复好友应幂等");
            assertTrue(db.areFriends("u-b", "u-a"), "关系应对称");
            List<FriendRow> friends = db.loadFriends("u-a");
            assertEquals(1, friends.size());
            assertEquals("Bob", friends.get(0).name());

            assertTrue(db.removeFriend("u-b", "u-a"));
            assertFalse(db.areFriends("u-a", "u-b"));
        } finally {
            db.close();
        }
    }

    @Test
    void reopensExistingDatabaseWithoutSchemaErrors(@TempDir Path dir) {
        SqliteDatabase first = open(dir);
        first.saveIdentity(new PlayerIdentity("u-1", "Alice", 1));
        first.close();

        SqliteDatabase second = open(dir);
        try {
            assertNotNull(second.loadIdentityByName("alice"));
            assertTrue(second.addFriend("u-1", "Alice", "u-2", "Bob", 2));
            assertTrue(second.areFriends("u-1", "u-2"));
        } finally {
            second.close();
        }
    }

    @Test
    void mailRoundTripAndPrefixLookup(@TempDir Path dir) {
        SqliteDatabase db = open(dir);
        try {
            long now = System.currentTimeMillis();
            MailRow mail = new MailRow("12345678-abcd-ef01-2345-6789abcdef01", "u-s", "Sender",
                    "u-r", "Recipient", "主题", "正文", List.of("ZGF0YQ"), now, 0, false, false);
            assertTrue(db.saveMail(mail));
            assertEquals(1, db.countMail("u-r", now));
            assertEquals(1, db.countUnread("u-r", now));
            assertEquals("主题", db.loadMail(mail.id()).subject());
            assertEquals("主题", db.loadMailByPrefix("u-r", "12345678").subject());
            assertNull(db.loadMailByPrefix("other", "12345678"));
            assertEquals(1, db.loadMail("u-r", now, 0, 10).size());
            assertEquals(1, db.countMailBySender("u-s", now));
            assertEquals("主题", db.loadMailBySenderPrefix("u-s", "12345678").subject());
            assertNull(db.loadMailBySenderPrefix("other", "12345678"));
            assertEquals(1, db.loadMailBySender("u-s", now, 0, 10).size());

            assertTrue(db.markMailRead(mail.id(), "u-r"));
            assertEquals(0, db.countUnread("u-r", now));
            assertTrue(db.markMailClaimed(mail.id(), "u-r"));
            assertFalse(db.markMailClaimed(mail.id(), "u-r"), "不能重复领取");
            MailRow sent = db.loadMailBySender("u-s", now, 0, 10).get(0);
            assertTrue(sent.read());
            assertTrue(sent.claimed());
            assertTrue(sent.readAt() > 0);
            assertTrue(sent.claimedAt() > 0);

            assertTrue(db.deleteMail(mail.id(), "u-r"));
            assertEquals(0, db.countMail("u-r", now));
            assertNull(db.loadMailByPrefix("u-r", "12345678"));
            assertEquals(1, db.countMailBySender("u-s", now), "收件人删除不应影响发件箱");
        } finally {
            db.close();
        }
    }

    @Test
    void sentMailSoftDeleteAndRecall(@TempDir Path dir) {
        SqliteDatabase db = open(dir);
        try {
            long now = System.currentTimeMillis();
            MailRow deleted = new MailRow("aaaa1111-abcd-ef01-2345-6789abcdef01", "u-s", "Sender",
                    "u-r", "Recipient", "删除发件", "正文", List.of(), now, 0, false, false);
            assertTrue(db.saveMail(deleted));
            assertTrue(db.deleteMailBySender(deleted.id(), "u-s"));
            assertEquals(0, db.countMailBySender("u-s", now));
            assertNull(db.loadMailBySenderPrefix("u-s", "aaaa1111"));
            assertEquals(1, db.countMail("u-r", now), "删除发件记录不应影响收件箱");

            MailRow recall = new MailRow("bbbb2222-abcd-ef01-2345-6789abcdef01", "u-s", "Sender",
                    "u-r", "Recipient", "撤回", "正文", List.of(), now, 0, false, false);
            assertTrue(db.saveMail(recall));
            assertTrue(db.recallMail(recall.id(), "u-s"));
            assertNull(db.loadMailByPrefix("u-r", "bbbb2222"));
            assertEquals(1, db.countMail("u-r", now), "被撤回邮件不应计入收件箱");
            MailRow recalled = db.loadMailBySenderPrefix("u-s", "bbbb2222");
            assertNotNull(recalled);
            assertTrue(recalled.recalled());

            MailRow read = new MailRow("cccc3333-abcd-ef01-2345-6789abcdef01", "u-s", "Sender",
                    "u-r", "Recipient", "已读", "正文", List.of(), now, 0, false, false);
            assertTrue(db.saveMail(read));
            assertTrue(db.markMailRead(read.id(), "u-r"));
            assertFalse(db.recallMail(read.id(), "u-s"), "已读邮件不能撤回");
        } finally {
            db.close();
        }
    }

    @Test
    void migratesOldMailSchema(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("liuchat.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE mail (
                        id VARCHAR(36) PRIMARY KEY,
                        sender_uuid VARCHAR(36) NOT NULL,
                        sender_name VARCHAR(32) NOT NULL,
                        recipient_uuid VARCHAR(36) NOT NULL,
                        recipient_name VARCHAR(32) NOT NULL,
                        subject VARCHAR(128) NOT NULL,
                        body TEXT NOT NULL,
                        attachments MEDIUMTEXT,
                        created_at BIGINT NOT NULL,
                        expires_at BIGINT NOT NULL,
                        is_read INT NOT NULL DEFAULT 0,
                        claimed INT NOT NULL DEFAULT 0
                    )""");
            statement.executeUpdate("INSERT INTO mail VALUES "
                    + "('old-id', 'u-s', 'Sender', 'u-r', 'Recipient', '旧邮件', '正文', '', 1, 0, 0, 0)");
        }
        SqliteDatabase db = open(dir);
        try {
            MailRow old = db.loadMail("old-id");
            assertNotNull(old);
            assertEquals(0, old.readAt());
            assertEquals(0, old.claimedAt());
            assertFalse(old.recalled());
            assertTrue(db.markMailRead("old-id", "u-r"));
            assertTrue(db.loadMail("old-id").readAt() > 0);
        } finally {
            db.close();
        }
    }

    @Test
    void expiredMailIsHiddenAndCleaned(@TempDir Path dir) {
        SqliteDatabase db = open(dir);
        try {
            long now = System.currentTimeMillis();
            MailRow expired = new MailRow("expired-id", "s", "S", "r", "R", "x", "y", List.of(), now - 1000, now - 1, false, false);
            assertTrue(db.saveMail(expired));
            assertEquals(0, db.countMail("r", now));
            assertTrue(db.loadMail("r", now, 0, 10).isEmpty());
            assertEquals(1, db.deleteExpiredMail(now));
            assertNull(db.loadMail(expired.id()));
        } finally {
            db.close();
        }
    }
}

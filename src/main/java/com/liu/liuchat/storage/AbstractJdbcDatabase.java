package com.liu.liuchat.storage;

import com.liu.liuchat.model.FriendRequestRow;
import com.liu.liuchat.model.FriendRow;
import com.liu.liuchat.model.MailRow;
import com.liu.liuchat.model.MuteData;
import com.liu.liuchat.model.PlayerIdentity;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * JDBC 通用实现：连接、建表、CRUD 模板全部在这里，
 * 方言差异（jdbc url / 驱动 / DDL / upsert SQL）由子类提供。
 * <p>
 * 所有 JDBC 操作经 {@link DbExecutor} 串行执行在专用工作线程上：
 * <ul>
 *   <li>写操作（保存/删除禁言、资料、屏蔽项）异步落库，主线程命令不等网络往返；</li>
 *   <li>读操作同步返回，且排在此前所有已入队写入之后（读己之写屏障）；</li>
 *   <li>组合操作（发喇叭 = 更新 + 回读）合成单个任务，原子且不自锁。</li>
 * </ul>
 * 初始化失败时降级为"仅内存"运行。
 */
abstract class AbstractJdbcDatabase implements Database {

    protected final java.util.logging.Logger logger;
    private final java.util.function.BooleanSupplier enabled;
    private volatile Connection connection;
    private volatile boolean ready;
    private final DbExecutor executor = new DbExecutor("LiuChat-db-writer");
    private final AtomicBoolean closed = new AtomicBoolean();
    /** 旧列宽兜底只提示一次，避免每封邮件刷屏。 */
    private volatile boolean warnedMailLegacyName;

    protected AbstractJdbcDatabase(JavaPlugin plugin) {
        this(plugin.getLogger(), plugin::isEnabled);
    }

    /** 测试/嵌入式场景可只提供日志与启用状态，不依赖 JavaPlugin。 */
    protected AbstractJdbcDatabase(java.util.logging.Logger logger,
                                   java.util.function.BooleanSupplier enabled) {
        this.logger = logger;
        this.enabled = enabled == null ? () -> true : enabled;
    }

    /** jdbc 连接串 */
    protected abstract String jdbcUrl();

    /** 驱动类全名 */
    protected abstract String driverClass();

    /** 建表 DDL（方言相关） */
    protected abstract String createTableSql();

    /** 插入或更新语句（SQLite: INSERT OR REPLACE / MySQL: ON DUPLICATE KEY UPDATE） */
    protected abstract String upsertSql();
    protected abstract String upsertProfileSql();

    /** 描述用的连接目标（日志展示） */
    protected abstract String describe();

    public synchronized boolean init() {
        if (ready) {
            return true;
        }
        try {
            Class.forName(driverClass());
            connection = DriverManager.getConnection(jdbcUrl(), username(), password());
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(createTableSql());
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS chat_ignore (owner VARCHAR(36) NOT NULL, "
                        + "ignored_name VARCHAR(32) NOT NULL, PRIMARY KEY (owner, ignored_name))");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS chat_profile (owner VARCHAR(36) PRIMARY KEY, "
                        + "nick TEXT, color TEXT)");
                migrateProfileSchema(statement);
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS horn_balance (owner VARCHAR(36) PRIMARY KEY, credits INT NOT NULL DEFAULT 0)");
                createSocialSchema(statement);
                migrateSocialSchema(statement);
            }
            ready = true;
        } catch (Exception e) {
            logger.log(Level.SEVERE,
                    "数据库初始化失败（" + describe() + "），相关数据暂不可用（重启丢失）", e);
        }
        return ready;
    }

    /** 非 SQLite 数据库的用户名；SQLite 子类返回 null 即可 */
    protected String username() {
        return null;
    }

    protected String password() {
        return null;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    /**
     * 查询失败时的自愈。
     * <p>
     * 原实现 {@code ready} 一旦为 true 就终身不变：MySQL 重启、{@code wait_timeout}（默认 8 小时）
     * 或网络抖动把连接掐断后，<b>所有写入会永久失败直到 MC 服重启</b>。
     * 这里在失败时检测连接健康度，坏了就重连，让下一次操作恢复。
     * <p>
     * 跑在 {@link DbExecutor} 的工作线程上，重连不会卡主线程。
     *
     * @return true = 刚刚完成了重连（本次操作仍未生效）
     */
    protected synchronized boolean tryReconnect() {
        if (!enabled.getAsBoolean()) return false;   // 关服过程中不要重建连接
        boolean healthy;
        try {
            healthy = connection != null && connection.isValid(2);
        } catch (Exception e) {
            healthy = false;
        }
        if (healthy) return false;                // 连接没坏，是别的错误，别瞎重连
        try {
            if (connection != null) connection.close();
        } catch (Exception ignored) {
            // 关不掉就算了，反正要丢弃
        }
        connection = null;
        ready = false;
        if (!init()) return false;
        logger.info("数据库连接已自动重连（" + describe() + "）");
        return true;
    }

    /** 统一的失败出口：能自愈就自愈并降级告警，否则按原样报严重错误。 */
    private void fail(String what, Exception e) {
        if (tryReconnect()) {
            logger.log(Level.WARNING, what + "（连接已自动重连，本次操作未生效，下一次会重试）", e);
        } else {
            logger.log(Level.SEVERE, what, e);
        }
    }

    /** Allows dialect-specific migrations for existing installations. */
    protected void migrateProfileSchema(Statement statement) {
    }

    // ---------------- 禁言 ----------------

    @Override
    public List<MuteData> loadMutes() {
        if (!ready) return new ArrayList<>();
        return executor.read(this::doLoadMutes);
    }

    private List<MuteData> doLoadMutes() {
        List<MuteData> result = new ArrayList<>();
        if (!ready) {
            return result;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                     "SELECT uuid, name, expire_at, reason, operator FROM mute");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(new MuteData(
                        rs.getString(1), rs.getString(2), rs.getLong(3),
                        rs.getString(4), rs.getString(5)));
            }
        } catch (Exception e) {
            fail("读取禁言数据失败", e);
            return null;
        }
        return result;
    }

    @Override
    public void saveMute(MuteData mute) {
        executor.write(() -> {
            if (!ready) return;
            try (PreparedStatement ps = connection.prepareStatement(upsertSql())) {
                ps.setString(1, mute.uuid());
                ps.setString(2, mute.name());
                ps.setLong(3, mute.expireAt());
                ps.setString(4, mute.reason());
                ps.setString(5, mute.operator());
                bindUpsertTail(ps, mute);
                ps.executeUpdate();
            } catch (Exception e) {
                fail("保存禁言数据失败", e);
            }
        });
    }

    /**
     * upsert 语句中 5 个通用占位符之后的额外参数绑定。
     * SQLite 的 INSERT OR REPLACE 不需要 → 空实现；
     * MySQL 的 ON DUPLICATE KEY UPDATE 需要把 1~5 再绑一遍。
     */
    protected void bindUpsertTail(PreparedStatement ps, MuteData mute) throws Exception {
    }

    @Override
    public void deleteMute(String uuid) {
        executor.write(() -> {
            if (!ready) return;
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM mute WHERE uuid = ?")) {
                ps.setString(1, uuid);
                ps.executeUpdate();
            } catch (Exception e) {
                fail("删除禁言数据失败", e);
            }
        });
    }

    // ---------------- 资料 ----------------

    @Override
    public Profile loadProfile(String owner) {
        if (!ready) return new Profile("", "");
        return executor.read(() -> doLoadProfile(owner));
    }

    private Profile doLoadProfile(String owner) {
        if (!ready) return new Profile("", "");
        try (PreparedStatement ps = connection.prepareStatement(
                     "SELECT nick, color FROM chat_profile WHERE owner = ?")) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new Profile(rs.getString(1) == null ? "" : rs.getString(1),
                        rs.getString(2) == null ? "" : rs.getString(2));
            }
        } catch (Exception e) { fail("读取聊天资料失败", e); }
        return new Profile("", "");
    }

    @Override
    public void saveProfile(String owner, Profile profile) {
        executor.write(() -> {
            if (!ready) return;
            try (PreparedStatement ps = connection.prepareStatement(upsertProfileSql())) {
                ps.setString(1, owner);
                ps.setString(2, profile.nick());
                ps.setString(3, profile.color());
                ps.executeUpdate();
            } catch (Exception e) { fail("保存聊天资料失败", e); }
        });
    }

    // ---------------- 屏蔽列表 ----------------

    @Override
    public List<String> loadIgnores(String owner) {
        if (!ready) return new ArrayList<>();
        return executor.read(() -> doLoadIgnores(owner));
    }

    private List<String> doLoadIgnores(String owner) {
        List<String> names = new ArrayList<>();
        if (!ready) return names;
        try (PreparedStatement ps = connection.prepareStatement(
                     "SELECT ignored_name FROM chat_ignore WHERE owner = ?")) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) names.add(rs.getString(1));
            }
        } catch (Exception e) { fail("读取屏蔽列表失败", e); }
        return names;
    }

    @Override
    public void addIgnore(String owner, String name) {
        executor.write(() -> {
            if (!ready) return;
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO chat_ignore (owner, ignored_name) VALUES (?, ?)")) {
                ps.setString(1, owner);
                ps.setString(2, name);
                ps.executeUpdate();
            } catch (Exception e) { fail("保存屏蔽项失败", e); }
        });
    }

    @Override
    public void removeIgnore(String owner, String name) {
        executor.write(() -> {
            if (!ready) return;
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM chat_ignore WHERE owner = ? AND ignored_name = ?")) {
                ps.setString(1, owner);
                ps.setString(2, name);
                ps.executeUpdate();
            } catch (Exception e) { fail("删除屏蔽项失败", e); }
        });
    }

    // ---------------- 喇叭 ----------------

    @Override
    public int hornBalance(String owner) {
        if (!ready) return -1;
        return executor.read(() -> doHornBalance(owner));
    }

    private int doHornBalance(String owner) {
        if (!ready) return -1;
        try (PreparedStatement ps = connection.prepareStatement(
                     "SELECT credits FROM horn_balance WHERE owner = ?")) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Math.max(0, rs.getInt(1)) : 0;
            }
        } catch (Exception e) {
            fail("读取喇叭数量失败", e);
            return -1;
        }
    }

    @Override
    public int addHorns(String owner, int amount) {
        if (!ready || amount <= 0) return -1;
        // 更新 + 回读合并成一个任务：同线程顺序执行，避免任务内再提交造成自锁
        return executor.read(() -> {
            if (!ready || amount <= 0) return -1;
            try (PreparedStatement ps = connection.prepareStatement(upsertHornSql())) {
                ps.setString(1, owner);
                ps.setInt(2, amount);
                ps.executeUpdate();
            } catch (Exception e) {
                fail("发放喇叭数量失败", e);
                return -1;
            }
            return doHornBalance(owner);
        });
    }

    @Override
    public boolean spendHorn(String owner) {
        if (!ready) return false;
        return executor.read(() -> {
            if (!ready) return false;
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE horn_balance SET credits = credits - 1 WHERE owner = ? AND credits > 0")) {
                ps.setString(1, owner);
                return ps.executeUpdate() == 1;
            } catch (Exception e) {
                fail("扣除喇叭数量失败", e);
                return false;
            }
        });
    }

    protected abstract String upsertHornSql();
    protected abstract String upsertIdentitySql();

    // ---------------- 好友/邮件建表 ----------------

    private void createSocialSchema(Statement statement) throws SQLException {
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS player_identity (
                    uuid      VARCHAR(36) PRIMARY KEY,
                    name      VARCHAR(32) NOT NULL,
                    name_key  VARCHAR(32) NOT NULL,
                    last_seen BIGINT NOT NULL
                )""");
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friend_link (
                    low_uuid   VARCHAR(36) NOT NULL,
                    high_uuid  VARCHAR(36) NOT NULL,
                    low_name   VARCHAR(32) NOT NULL,
                    high_name  VARCHAR(32) NOT NULL,
                    created_at BIGINT NOT NULL,
                    PRIMARY KEY (low_uuid, high_uuid)
                )""");
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friend_request (
                    sender_uuid VARCHAR(36) NOT NULL,
                    sender_name VARCHAR(32) NOT NULL,
                    target_uuid VARCHAR(36) NOT NULL,
                    target_name VARCHAR(32) NOT NULL,
                    created_at  BIGINT NOT NULL,
                    expires_at  BIGINT NOT NULL,
                    PRIMARY KEY (sender_uuid, target_uuid)
                )""");
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS mail (
                    id             VARCHAR(36) PRIMARY KEY,
                    sender_uuid    VARCHAR(36) NOT NULL,
                    sender_name    VARCHAR(128) NOT NULL,
                    recipient_uuid VARCHAR(36) NOT NULL,
                    recipient_name VARCHAR(128) NOT NULL,
                    subject        VARCHAR(128) NOT NULL,
                    body           TEXT NOT NULL,
                    attachments    MEDIUMTEXT,
                    created_at     BIGINT NOT NULL,
                    expires_at     BIGINT NOT NULL,
                    is_read        INT NOT NULL DEFAULT 0,
                    claimed        INT NOT NULL DEFAULT 0,
                    read_at        BIGINT NOT NULL DEFAULT 0,
                    claimed_at     BIGINT NOT NULL DEFAULT 0,
                    recalled       INT NOT NULL DEFAULT 0,
                    sender_deleted INT NOT NULL DEFAULT 0,
                    recipient_deleted INT NOT NULL DEFAULT 0
                )""");
        tryIndex(statement, "CREATE INDEX friend_link_high ON friend_link(high_uuid)");
        tryIndex(statement, "CREATE INDEX friend_request_target ON friend_request(target_uuid)");
        tryIndex(statement, "CREATE INDEX player_identity_name ON player_identity(name_key)");
        tryIndex(statement, "CREATE INDEX mail_recipient ON mail(recipient_uuid, created_at)");
        tryIndex(statement, "CREATE INDEX mail_sender ON mail(sender_uuid, created_at)");
    }

    /** 旧库升级钩子：方言相关的列类型迁移。 */
    protected void migrateSocialSchema(Statement statement) {
        addColumn(statement, "mail", "read_at", "BIGINT NOT NULL DEFAULT 0");
        addColumn(statement, "mail", "claimed_at", "BIGINT NOT NULL DEFAULT 0");
        addColumn(statement, "mail", "recalled", "INT NOT NULL DEFAULT 0");
        addColumn(statement, "mail", "sender_deleted", "INT NOT NULL DEFAULT 0");
        addColumn(statement, "mail", "recipient_deleted", "INT NOT NULL DEFAULT 0");
    }

    private static void addColumn(Statement statement, String table, String column, String definition) {
        try {
            statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (SQLException ignored) {
            // 列已存在
        }
    }

    private static void tryIndex(Statement statement, String sql) {
        try {
            statement.executeUpdate(sql);
        } catch (SQLException ignored) {
            // 索引已存在（SQLite/MySQL 对重复索引的报错不同，统一忽略）
        }
    }

    // ---------------- 玩家名目录 ----------------

    @Override
    public void saveIdentity(PlayerIdentity identity) {
        if (identity == null) return;
        executor.write(() -> {
            if (!ready) return;
            try (PreparedStatement ps = connection.prepareStatement(upsertIdentitySql())) {
                ps.setString(1, identity.uuid());
                ps.setString(2, identity.name());
                ps.setString(3, identity.name().toLowerCase(java.util.Locale.ROOT));
                ps.setLong(4, identity.lastSeen());
                ps.executeUpdate();
            } catch (Exception e) {
                fail("保存玩家名目录失败", e);
            }
        });
    }

    @Override
    public PlayerIdentity loadIdentityByName(String nameKey) {
        if (!ready || nameKey == null) return null;
        return executor.read(() -> {
            if (!ready) return null;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT uuid, name, last_seen FROM player_identity WHERE name_key = ? "
                            + "ORDER BY last_seen DESC LIMIT 1")) {
                ps.setString(1, nameKey.toLowerCase(java.util.Locale.ROOT));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new PlayerIdentity(rs.getString(1), rs.getString(2), rs.getLong(3)) : null;
                }
            } catch (Exception e) {
                fail("读取玩家名目录失败", e);
                return null;
            }
        });
    }

    @Override
    public PlayerIdentity loadIdentityByUuid(String uuid) {
        if (!ready || uuid == null) return null;
        return executor.read(() -> {
            if (!ready) return null;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT uuid, name, last_seen FROM player_identity WHERE uuid = ?")) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new PlayerIdentity(rs.getString(1), rs.getString(2), rs.getLong(3)) : null;
                }
            } catch (Exception e) {
                fail("读取玩家名目录失败", e);
                return null;
            }
        });
    }

    // ---------------- 好友 ----------------

    @Override
    public List<FriendRow> loadFriends(String uuid) {
        if (!ready || uuid == null) return new ArrayList<>();
        return executor.read(() -> {
            List<FriendRow> friends = new ArrayList<>();
            if (!ready) return friends;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT low_uuid, low_name, high_uuid, high_name, created_at FROM friend_link "
                            + "WHERE low_uuid = ? OR high_uuid = ?")) {
                ps.setString(1, uuid);
                ps.setString(2, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String low = rs.getString(1);
                        String high = rs.getString(3);
                        if (uuid.equals(low)) friends.add(new FriendRow(high, rs.getString(4), rs.getLong(5)));
                        else friends.add(new FriendRow(low, rs.getString(2), rs.getLong(5)));
                    }
                }
            } catch (Exception e) {
                fail("读取好友列表失败", e);
                return null;
            }
            return friends;
        });
    }

    @Override
    public boolean areFriends(String first, String second) {
        if (!ready || first == null || second == null) return false;
        String[] pair = canonical(first, second);
        return executor.read(() -> {
            if (!ready) return false;
            return friendExists(pair[0], pair[1]);
        });
    }

    private boolean friendExists(String low, String high) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM friend_link WHERE low_uuid = ? AND high_uuid = ?")) {
            ps.setString(1, low);
            ps.setString(2, high);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            fail("查询好友关系失败", e);
            return false;
        }
    }

    @Override
    public boolean addFriend(String lowUuid, String lowName, String highUuid, String highName, long since) {
        if (!ready || lowUuid == null || highUuid == null || lowUuid.equals(highUuid)) return false;
        return executor.read(() -> {
            if (!ready) return false;
            if (friendExists(lowUuid, highUuid)) return false;
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO friend_link (low_uuid, high_uuid, low_name, high_name, created_at) "
                            + "VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, lowUuid);
                ps.setString(2, highUuid);
                ps.setString(3, lowName);
                ps.setString(4, highName);
                ps.setLong(5, since);
                return ps.executeUpdate() == 1;
            } catch (SQLException error) {
                if (isDuplicate(error)) return false; // 另一台子服同时写入：按已存在处理
                fail("保存好友关系失败", error);
                return false;
            } catch (Exception e) {
                fail("保存好友关系失败", e);
                return false;
            }
        });
    }

    @Override
    public boolean removeFriend(String first, String second) {
        if (!ready || first == null || second == null) return false;
        String[] pair = canonical(first, second);
        return executor.read(() -> {
            if (!ready) return false;
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM friend_link WHERE low_uuid = ? AND high_uuid = ?")) {
                ps.setString(1, pair[0]);
                ps.setString(2, pair[1]);
                return ps.executeUpdate() == 1;
            } catch (Exception e) {
                fail("删除好友关系失败", e);
                return false;
            }
        });
    }

    @Override
    public List<FriendRequestRow> loadIncomingRequests(String uuid, long now) {
        if (!ready || uuid == null) return new ArrayList<>();
        return executor.read(() -> loadRequests("SELECT sender_uuid, sender_name, target_uuid, target_name, "
                + "created_at, expires_at FROM friend_request WHERE target_uuid = ? AND expires_at > ? "
                + "ORDER BY created_at DESC", uuid, now));
    }

    @Override
    public List<FriendRequestRow> loadOutgoingRequests(String uuid, long now) {
        if (!ready || uuid == null) return new ArrayList<>();
        return executor.read(() -> loadRequests("SELECT sender_uuid, sender_name, target_uuid, target_name, "
                + "created_at, expires_at FROM friend_request WHERE sender_uuid = ? AND expires_at > ? "
                + "ORDER BY created_at DESC", uuid, now));
    }

    private List<FriendRequestRow> loadRequests(String sql, String uuid, long now) {
        List<FriendRequestRow> requests = new ArrayList<>();
        if (!ready) return requests;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid);
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    requests.add(new FriendRequestRow(rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getString(4), rs.getLong(5), rs.getLong(6)));
                }
            }
        } catch (Exception e) {
            fail("读取好友请求失败", e);
            return null;
        }
        return requests;
    }

    @Override
    public FriendRequestRow loadFriendRequest(String senderUuid, String targetUuid) {
        if (!ready || senderUuid == null || targetUuid == null) return null;
        return executor.read(() -> {
            if (!ready) return null;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT sender_uuid, sender_name, target_uuid, target_name, created_at, expires_at "
                            + "FROM friend_request WHERE sender_uuid = ? AND target_uuid = ?")) {
                ps.setString(1, senderUuid);
                ps.setString(2, targetUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new FriendRequestRow(rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getString(4), rs.getLong(5), rs.getLong(6)) : null;
                }
            } catch (Exception e) {
                fail("读取好友请求失败", e);
                return null;
            }
        });
    }

    @Override
    public boolean addFriendRequest(FriendRequestRow request) {
        if (!ready || request == null) return false;
        String[] pair = canonical(request.senderUuid(), request.targetUuid());
        return executor.read(() -> {
            if (!ready) return false;
            if (friendExists(pair[0], pair[1])) return false;
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO friend_request (sender_uuid, sender_name, target_uuid, target_name, "
                            + "created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, request.senderUuid());
                ps.setString(2, request.senderName());
                ps.setString(3, request.targetUuid());
                ps.setString(4, request.targetName());
                ps.setLong(5, request.createdAt());
                ps.setLong(6, request.expiresAt());
                return ps.executeUpdate() == 1;
            } catch (SQLException error) {
                if (isDuplicate(error)) return false;
                fail("保存好友请求失败", error);
                return false;
            } catch (Exception e) {
                fail("保存好友请求失败", e);
                return false;
            }
        });
    }

    @Override
    public boolean removeFriendRequest(String senderUuid, String targetUuid) {
        if (!ready || senderUuid == null || targetUuid == null) return false;
        return executor.read(() -> {
            if (!ready) return false;
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM friend_request WHERE sender_uuid = ? AND target_uuid = ?")) {
                ps.setString(1, senderUuid);
                ps.setString(2, targetUuid);
                return ps.executeUpdate() >= 1;
            } catch (Exception e) {
                fail("删除好友请求失败", e);
                return false;
            }
        });
    }

    @Override
    public int removeFriendRequestsBetween(String first, String second) {
        if (!ready || first == null || second == null) return 0;
        return executor.read(() -> {
            if (!ready) return 0;
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM friend_request WHERE (sender_uuid = ? AND target_uuid = ?) "
                            + "OR (sender_uuid = ? AND target_uuid = ?)")) {
                ps.setString(1, first);
                ps.setString(2, second);
                ps.setString(3, second);
                ps.setString(4, first);
                return ps.executeUpdate();
            } catch (Exception e) {
                fail("清理好友请求失败", e);
                return 0;
            }
        });
    }

    @Override
    public int removeExpiredFriendRequests(long now) {
        if (!ready) return 0;
        return executor.read(() -> {
            if (!ready) return 0;
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM friend_request WHERE expires_at > 0 AND expires_at <= ?")) {
                ps.setLong(1, now);
                return ps.executeUpdate();
            } catch (Exception e) {
                fail("清理过期好友请求失败", e);
                return 0;
            }
        });
    }

    // ---------------- 邮件 ----------------

    /**
     * 邮件表里名字列的最大字符数（按 Unicode 码点计，和 MySQL utf8mb4 的 VARCHAR 一致）。
     * 玩家名最多 16，但邮件 API 允许外部插件（如工会插件）传入任意展示名，
     * 旧库的 VARCHAR(32) 会因此抛 {@code Data too long for column 'recipient_name'}。
     */
    static final int MAIL_NAME_MAX = 128;
    /** 旧库尚未完成列宽迁移时的兜底列宽（VARCHAR(32) 时代）。 */
    static final int MAIL_NAME_LEGACY_MAX = 32;

    /** 截断到列宽且不把代理对（emoji/生僻字）从中间劈开，避免 MySQL 报非法字符串。 */
    static String clampName(String value) {
        return clampName(value, MAIL_NAME_MAX);
    }

    static String clampName(String value, int max) {
        if (value == null) return "";
        if (value.codePointCount(0, value.length()) <= max) return value;
        return value.substring(0, value.offsetByCodePoints(0, max));
    }

    @Override
    public boolean saveMail(MailRow mail) {
        if (mail == null) return false;
        return executor.read(() -> {
            if (!ready) return false;
            try {
                return insertMail(mail, MAIL_NAME_MAX);
            } catch (SQLException error) {
                if (!isNameTooLong(error)) {
                    fail("保存邮件失败", error);
                    return false;
                }
                // 列宽迁移没生效（例如数据库账号缺少 ALTER 权限）时退回旧列宽，
                // 保证外部插件的邮件仍然能投递，而不是整封丢失。
                if (!warnedMailLegacyName) {
                    warnedMailLegacyName = true;
                    logger.log(Level.WARNING,
                            "mail 名字列仍是旧列宽（VARCHAR(32)），已按 32 字符截断保存；"
                                    + "建议确认数据库账号有 ALTER 权限后重启以完成升级", error);
                }
                try {
                    return insertMail(mail, MAIL_NAME_LEGACY_MAX);
                } catch (SQLException retry) {
                    fail("保存邮件失败", retry);
                    return false;
                }
            }
        });
    }

    private boolean insertMail(MailRow mail, int nameMax) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO mail (id, sender_uuid, sender_name, recipient_uuid, recipient_name, "
                        + "subject, body, attachments, created_at, expires_at, is_read, claimed, "
                        + "read_at, claimed_at, recalled) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, mail.id());
            ps.setString(2, mail.senderUuid());
            ps.setString(3, clampName(mail.senderName(), nameMax));
            ps.setString(4, mail.recipientUuid());
            ps.setString(5, clampName(mail.recipientName(), nameMax));
            ps.setString(6, mail.subject());
            ps.setString(7, mail.body());
            ps.setString(8, String.join("\n", mail.attachments()));
            ps.setLong(9, mail.createdAt());
            ps.setLong(10, mail.expiresAt());
            ps.setInt(11, mail.read() ? 1 : 0);
            ps.setInt(12, mail.claimed() ? 1 : 0);
            ps.setLong(13, mail.readAt());
            ps.setLong(14, mail.claimedAt());
            ps.setInt(15, mail.recalled() ? 1 : 0);
            return ps.executeUpdate() == 1;
        }
    }

    /** MySQL/MariaDB 的「字符串超长」错误（SQLState 22001）。 */
    static boolean isNameTooLong(SQLException error) {
        String state = error.getSQLState();
        if (state != null && state.startsWith("22001")) return true;
        String message = error.getMessage();
        if (message == null) return false;
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("data too long") || lower.contains("data truncation")
                || lower.contains("string data, right truncation");
    }

    @Override
    public MailRow loadMail(String id) {
        if (!ready || id == null) return null;
        return executor.read(() -> {
            if (!ready) return null;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, sender_uuid, sender_name, recipient_uuid, recipient_name, subject, body, "
                            + "attachments, created_at, expires_at, is_read, claimed, read_at, claimed_at, recalled FROM mail WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? mapMail(rs) : null;
                }
            } catch (Exception e) {
                fail("读取邮件失败", e);
                return null;
            }
        });
    }

    @Override
    public MailRow loadMailByPrefix(String recipientUuid, String prefix) {
        if (!ready || recipientUuid == null || prefix == null || prefix.length() < 4) return null;
        return executor.read(() -> {
            if (!ready) return null;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, sender_uuid, sender_name, recipient_uuid, recipient_name, subject, body, "
                            + "attachments, created_at, expires_at, is_read, claimed, read_at, claimed_at, recalled FROM mail "
                            + "WHERE recipient_uuid = ? AND id LIKE ? AND recipient_deleted = 0 AND recalled = 0 "
                            + "AND (expires_at = 0 OR expires_at > ?) "
                            + "ORDER BY created_at DESC LIMIT 1")) {
                ps.setString(1, recipientUuid);
                ps.setString(2, prefix + "%");
                ps.setLong(3, System.currentTimeMillis());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? mapMail(rs) : null;
                }
            } catch (Exception e) {
                fail("读取邮件失败", e);
                return null;
            }
        });
    }

    @Override
    public List<MailRow> loadMail(String recipientUuid, long now, int offset, int limit) {
        if (!ready || recipientUuid == null) return new ArrayList<>();
        return executor.read(() -> {
            List<MailRow> mails = new ArrayList<>();
            if (!ready) return mails;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, sender_uuid, sender_name, recipient_uuid, recipient_name, subject, body, "
                            + "attachments, created_at, expires_at, is_read, claimed, read_at, claimed_at, recalled FROM mail "
                            + "WHERE recipient_uuid = ? AND recipient_deleted = 0 AND recalled = 0 "
                            + "AND (expires_at = 0 OR expires_at > ?) "
                            + "ORDER BY created_at DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, recipientUuid);
                ps.setLong(2, now);
                ps.setInt(3, Math.max(1, Math.min(100, limit)));
                ps.setInt(4, Math.max(0, offset));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) mails.add(mapMail(rs));
                }
            } catch (Exception e) {
                fail("读取邮箱失败", e);
                return null;
            }
            return mails;
        });
    }

    @Override
    public int countMail(String recipientUuid, long now) {
        if (!ready || recipientUuid == null) return 0;
        return executor.read(() -> countSql("SELECT COUNT(*) FROM mail WHERE recipient_uuid = ? "
                + "AND recipient_deleted = 0 AND recalled = 0 "
                + "AND (expires_at = 0 OR expires_at > ?)", recipientUuid, now));
    }

    @Override
    public int countUnread(String recipientUuid, long now) {
        if (!ready || recipientUuid == null) return 0;
        return executor.read(() -> countSql("SELECT COUNT(*) FROM mail WHERE recipient_uuid = ? "
                + "AND recipient_deleted = 0 AND recalled = 0 AND is_read = 0 "
                + "AND (expires_at = 0 OR expires_at > ?)", recipientUuid, now));
    }

    private int countSql(String sql, String recipientUuid, long now) {
        if (!ready) return 0;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, recipientUuid);
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            fail("统计邮件失败", e);
            return 0;
        }
    }

    @Override
    public List<MailRow> loadMailBySender(String senderUuid, long now, int offset, int limit) {
        if (!ready || senderUuid == null) return List.of();
        return executor.read(() -> {
            List<MailRow> mails = new ArrayList<>();
            if (!ready) return mails;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, sender_uuid, sender_name, recipient_uuid, recipient_name, subject, body, "
                            + "attachments, created_at, expires_at, is_read, claimed, read_at, claimed_at, recalled FROM mail "
                            + "WHERE sender_uuid = ? AND sender_deleted = 0 "
                            + "AND (expires_at = 0 OR expires_at > ?) "
                            + "ORDER BY created_at DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, senderUuid);
                ps.setLong(2, now);
                ps.setInt(3, Math.max(1, Math.min(100, limit)));
                ps.setInt(4, Math.max(0, offset));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) mails.add(mapMail(rs));
                }
            } catch (Exception e) {
                fail("读取发件箱失败", e);
                return null;
            }
            return mails;
        });
    }

    @Override
    public int countMailBySender(String senderUuid, long now) {
        if (!ready || senderUuid == null) return 0;
        return executor.read(() -> {
            if (!ready) return 0;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM mail WHERE sender_uuid = ? AND sender_deleted = 0 "
                            + "AND (expires_at = 0 OR expires_at > ?)")) {
                ps.setString(1, senderUuid);
                ps.setLong(2, now);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            } catch (Exception e) {
                fail("统计发件箱失败", e);
                return 0;
            }
        });
    }

    @Override
    public MailRow loadMailBySenderPrefix(String senderUuid, String prefix) {
        if (!ready || senderUuid == null || prefix == null) return null;
        return executor.read(() -> {
            if (!ready) return null;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, sender_uuid, sender_name, recipient_uuid, recipient_name, subject, body, "
                            + "attachments, created_at, expires_at, is_read, claimed, read_at, claimed_at, recalled FROM mail "
                            + "WHERE sender_uuid = ? AND id LIKE ? AND sender_deleted = 0 "
                            + "AND (expires_at = 0 OR expires_at > ?) "
                            + "ORDER BY created_at DESC LIMIT 1")) {
                ps.setString(1, senderUuid);
                ps.setString(2, prefix + "%");
                ps.setLong(3, System.currentTimeMillis());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? mapMail(rs) : null;
                }
            } catch (Exception e) {
                fail("按前缀读取发件箱失败", e);
                return null;
            }
        });
    }

    @Override
    public boolean markMailRead(String id, String recipientUuid) {
        return updateMail("UPDATE mail SET is_read = 1, "
                        + "read_at = CASE WHEN read_at = 0 THEN ? ELSE read_at END "
                        + "WHERE id = ? AND recipient_uuid = ? AND recipient_deleted = 0 AND recalled = 0",
                id, recipientUuid, System.currentTimeMillis());
    }

    @Override
    public boolean markMailClaimed(String id, String recipientUuid) {
        return updateMail("UPDATE mail SET claimed = 1, claimed_at = ? WHERE id = ? AND recipient_uuid = ? "
                        + "AND claimed = 0 AND recipient_deleted = 0 AND recalled = 0",
                id, recipientUuid, System.currentTimeMillis());
    }

    @Override
    public boolean deleteMail(String id, String recipientUuid) {
        return updateMail("UPDATE mail SET recipient_deleted = 1 WHERE id = ? AND recipient_uuid = ? "
                + "AND recipient_deleted = 0", id, recipientUuid);
    }

    @Override
    public boolean deleteMailBySender(String id, String senderUuid) {
        return updateMail("UPDATE mail SET sender_deleted = 1 WHERE id = ? AND sender_uuid = ? "
                + "AND sender_deleted = 0", id, senderUuid);
    }

    @Override
    public boolean recallMail(String id, String senderUuid) {
        return updateMail("UPDATE mail SET recalled = 1 WHERE id = ? AND sender_uuid = ? "
                + "AND recalled = 0 AND is_read = 0 AND claimed = 0", id, senderUuid);
    }

    private boolean updateMail(String sql, String id, String ownerUuid) {
        return updateMail(sql, id, ownerUuid, -1);
    }

    private boolean updateMail(String sql, String id, String ownerUuid, long timestamp) {
        if (!ready || id == null || ownerUuid == null) return false;
        return executor.read(() -> {
            if (!ready) return false;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                int index = 1;
                if (timestamp >= 0) ps.setLong(index++, timestamp);
                ps.setString(index++, id);
                ps.setString(index, ownerUuid);
                return ps.executeUpdate() >= 1;
            } catch (Exception e) {
                fail("更新邮件失败", e);
                return false;
            }
        });
    }

    @Override
    public int deleteExpiredMail(long now) {
        if (!ready) return 0;
        return executor.read(() -> {
            if (!ready) return 0;
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM mail WHERE expires_at > 0 AND expires_at <= ?")) {
                ps.setLong(1, now);
                return ps.executeUpdate();
            } catch (Exception e) {
                fail("清理过期邮件失败", e);
                return 0;
            }
        });
    }

    private static MailRow mapMail(ResultSet rs) throws SQLException {
        String attachments = rs.getString(8);
        List<String> parts = new ArrayList<>();
        if (attachments != null && !attachments.isBlank()) {
            for (String part : attachments.split("\n")) {
                if (!part.isBlank()) parts.add(part);
            }
        }
        return new MailRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), parts,
                rs.getLong(9), rs.getLong(10), rs.getInt(11) != 0, rs.getInt(12) != 0,
                rs.getLong(13), rs.getLong(14), rs.getInt(15) != 0);
    }

    private static String[] canonical(String first, String second) {
        return first.compareTo(second) <= 0 ? new String[]{first, second} : new String[]{second, first};
    }

    private static boolean isDuplicate(SQLException error) {
        String state = error.getSQLState();
        return state != null && state.startsWith("23")
                || error.getErrorCode() == 1062 || error.getErrorCode() == 19;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // 先排空写队列（停服前把异步落库刷完），再按 ready → connection 顺序关闭，
        // 让并发读先在 ready 检查上短路，避免触碰已关闭的连接
        if (!executor.close(10)) {
            logger.warning("数据库队列未在 10 秒内排空，可能有未落盘的写入");
        }
        ready = false;
        Connection open = connection;
        connection = null;
        if (open != null) {
            try {
                open.close();
            } catch (Exception ignored) {
                // 关闭失败无须处理
            }
        }
    }
}

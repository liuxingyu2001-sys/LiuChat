package com.liu.liuchat.storage;

import com.liu.liuchat.model.MuteData;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

    protected final JavaPlugin plugin;
    private volatile Connection connection;
    private volatile boolean ready;
    private final DbExecutor executor = new DbExecutor("LiuChat-db-writer");
    private final AtomicBoolean closed = new AtomicBoolean();

    protected AbstractJdbcDatabase(JavaPlugin plugin) {
        this.plugin = plugin;
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
            }
            ready = true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE,
                    "数据库初始化失败（" + describe() + "），禁言数据仅保存在内存（重启丢失）", e);
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
        if (!plugin.isEnabled()) return false;   // 关服过程中不要重建连接
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
        plugin.getLogger().info("数据库连接已自动重连（" + describe() + "）");
        return true;
    }

    /** 统一的失败出口：能自愈就自愈并降级告警，否则按原样报严重错误。 */
    private void fail(String what, Exception e) {
        if (tryReconnect()) {
            plugin.getLogger().log(Level.WARNING, what + "（连接已自动重连，本次操作未生效，下一次会重试）", e);
        } else {
            plugin.getLogger().log(Level.SEVERE, what, e);
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

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // 先排空写队列（停服前把异步落库刷完），再按 ready → connection 顺序关闭，
        // 让并发读先在 ready 检查上短路，避免触碰已关闭的连接
        if (!executor.close(10)) {
            plugin.getLogger().warning("数据库队列未在 10 秒内排空，可能有未落盘的写入");
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

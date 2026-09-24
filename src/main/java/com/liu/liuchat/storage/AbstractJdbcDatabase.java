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
import java.util.logging.Level;

/**
 * JDBC 通用实现：连接、建表、CRUD 模板全部在这里，
 * 方言差异（jdbc url / 驱动 / DDL / upsert SQL）由子类提供。
 * <p>
 * 所有方法同步；初始化失败时降级为"仅内存"运行。
 */
abstract class AbstractJdbcDatabase implements Database {

    protected final JavaPlugin plugin;
    private Connection connection;
    private boolean ready;

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

    /** 非 SQLite 数据库的用户名，SQLite 子类返回 null 即可 */
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

    /** Allows dialect-specific migrations for existing installations. */
    protected void migrateProfileSchema(Statement statement) {
    }

    @Override
    public synchronized List<MuteData> loadMutes() {
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
            plugin.getLogger().log(Level.SEVERE, "读取禁言数据失败", e);
            return null;
        }
        return result;
    }

    @Override
    public synchronized void saveMute(MuteData mute) {
        if (!ready) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(upsertSql())) {
            ps.setString(1, mute.uuid());
            ps.setString(2, mute.name());
            ps.setLong(3, mute.expireAt());
            ps.setString(4, mute.reason());
            ps.setString(5, mute.operator());
            bindUpsertTail(ps, mute);
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "保存禁言数据失败", e);
        }
    }

    /**
     * upsert 语句中 5 个通用占位符之后的额外参数绑定。
     * SQLite 的 INSERT OR REPLACE 不需要 → 空实现；
     * MySQL 的 ON DUPLICATE KEY UPDATE 需要把 1~5 再绑一遍。
     */
    protected void bindUpsertTail(PreparedStatement ps, MuteData mute) throws Exception {
    }

    @Override
    public synchronized void deleteMute(String uuid) {
        if (!ready) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM mute WHERE uuid = ?")) {
            ps.setString(1, uuid);
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "删除禁言数据失败", e);
        }
    }

    @Override
    public synchronized Profile loadProfile(String owner) {
        if (!ready) return new Profile("", "");
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT nick, color FROM chat_profile WHERE owner = ?")) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new Profile(rs.getString(1) == null ? "" : rs.getString(1),
                        rs.getString(2) == null ? "" : rs.getString(2));
            }
        } catch (Exception e) { plugin.getLogger().log(Level.SEVERE, "读取聊天资料失败", e); }
        return new Profile("", "");
    }

    @Override
    public synchronized void saveProfile(String owner, Profile profile) {
        if (!ready) return;
        try (PreparedStatement ps = connection.prepareStatement(upsertProfileSql())) {
            ps.setString(1, owner);
            ps.setString(2, profile.nick());
            ps.setString(3, profile.color());
            ps.executeUpdate();
        } catch (Exception e) { plugin.getLogger().log(Level.SEVERE, "保存聊天资料失败", e); }
    }

    @Override
    public synchronized List<String> loadIgnores(String owner) {
        List<String> names = new ArrayList<>();
        if (!ready) return names;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT ignored_name FROM chat_ignore WHERE owner = ?")) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) names.add(rs.getString(1));
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "读取屏蔽列表失败", e);
        }
        return names;
    }

    @Override
    public synchronized void addIgnore(String owner, String name) {
        if (!ready) return;
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO chat_ignore (owner, ignored_name) VALUES (?, ?)")) {
            ps.setString(1, owner);
            ps.setString(2, name);
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "保存屏蔽列表失败", e);
        }
    }

    @Override
    public synchronized void removeIgnore(String owner, String name) {
        if (!ready) return;
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM chat_ignore WHERE owner = ? AND ignored_name = ?")) {
            ps.setString(1, owner);
            ps.setString(2, name);
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "删除屏蔽项失败", e);
        }
    }

    @Override
    public synchronized int hornBalance(String owner) {
        if (!ready) return -1;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT credits FROM horn_balance WHERE owner = ?")) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Math.max(0, rs.getInt(1)) : 0;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "读取喇叭数量失败", e);
            return -1;
        }
    }

    @Override
    public synchronized int addHorns(String owner, int amount) {
        if (!ready || amount <= 0) return -1;
        try (PreparedStatement ps = connection.prepareStatement(upsertHornSql())) {
            ps.setString(1, owner);
            ps.setInt(2, amount);
            ps.executeUpdate();
            return hornBalance(owner);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "发放喇叭数量失败", e);
            return -1;
        }
    }

    @Override
    public synchronized boolean spendHorn(String owner) {
        if (!ready) return false;
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE horn_balance SET credits = credits - 1 WHERE owner = ? AND credits > 0")) {
            ps.setString(1, owner);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "扣除喇叭数量失败", e);
            return false;
        }
    }

    protected abstract String upsertHornSql();

    @Override
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {
                // 关闭失败无须处理
            }
            connection = null;
        }
        ready = false;
    }
}

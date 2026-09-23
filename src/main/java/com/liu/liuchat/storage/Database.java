package com.liu.liuchat.storage;

import com.liu.liuchat.model.MuteData;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * SQLite 封装（驱动由 plugin.yml 的 libraries 提供，不打进 jar）。
 * <p>
 * 所有方法同步；初始化失败时降级为"仅内存"运行，不阻塞插件启用。
 */
public final class Database {

    private final JavaPlugin plugin;
    private Connection connection;
    private boolean ready;

    public Database(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized boolean init() {
        if (ready) {
            return true;
        }
        try {
            File folder = plugin.getDataFolder();
            if (!folder.exists() && !folder.mkdirs()) {
                throw new IllegalStateException("无法创建数据目录: " + folder);
            }
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + new File(folder, "liuchat.db").getAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS mute (
                            uuid      VARCHAR(36) PRIMARY KEY,
                            name      VARCHAR(32) NOT NULL,
                            expire_at BIGINT      NOT NULL,
                            reason    VARCHAR(255),
                            operator  VARCHAR(32)
                        )""");
            }
            ready = true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE,
                    "SQLite 初始化失败，禁言数据仅保存在内存（重启丢失）", e);
        }
        return ready;
    }

    public boolean isReady() {
        return ready;
    }

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
        }
        return result;
    }

    public synchronized void saveMute(MuteData mute) {
        if (!ready) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO mute (uuid, name, expire_at, reason, operator) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, mute.uuid());
            ps.setString(2, mute.name());
            ps.setLong(3, mute.expireAt());
            ps.setString(4, mute.reason());
            ps.setString(5, mute.operator());
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "保存禁言数据失败", e);
        }
    }

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

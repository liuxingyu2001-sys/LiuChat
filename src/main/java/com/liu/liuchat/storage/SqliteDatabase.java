package com.liu.liuchat.storage;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * SQLite 实现：本地文件存储，单服用。
 * 驱动经 plugin.yml 的 libraries 提供。
 */
final class SqliteDatabase extends AbstractJdbcDatabase {

    private final File dbFile;

    SqliteDatabase(JavaPlugin plugin) {
        super(plugin);
        this.dbFile = new File(plugin.getDataFolder(), "liuchat.db");
    }

    @Override
    protected String jdbcUrl() {
        return "jdbc:sqlite:" + dbFile.getAbsolutePath();
    }

    @Override
    protected String driverClass() {
        return "org.sqlite.JDBC";
    }

    @Override
    protected String createTableSql() {
        return """
                CREATE TABLE IF NOT EXISTS mute (
                    uuid      VARCHAR(36) PRIMARY KEY,
                    name      VARCHAR(32) NOT NULL,
                    expire_at BIGINT      NOT NULL,
                    reason    VARCHAR(255),
                    operator  VARCHAR(32)
                )""";
    }

    @Override
    protected String upsertSql() {
        return "INSERT OR REPLACE INTO mute (uuid, name, expire_at, reason, operator) VALUES (?, ?, ?, ?, ?)";
    }

    protected String upsertProfileSql() {
        return "INSERT INTO chat_profile (owner, nick, color) VALUES (?, ?, ?) "
                + "ON CONFLICT(owner) DO UPDATE SET nick = excluded.nick, color = excluded.color";
    }

    @Override
    protected String describe() {
        return dbFile.getAbsolutePath();
    }
}

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
        this(new File(plugin.getDataFolder(), "liuchat.db"), plugin.getLogger());
    }

    /** 测试用：直接指定数据库文件，不依赖 Bukkit。 */
    SqliteDatabase(File dbFile, java.util.logging.Logger logger) {
        super(logger, () -> true);
        this.dbFile = dbFile;
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
    protected String upsertHornSql() {
        return "INSERT INTO horn_balance (owner, credits) VALUES (?, ?) "
                + "ON CONFLICT(owner) DO UPDATE SET credits = credits + excluded.credits";
    }

    @Override
    protected String upsertIdentitySql() {
        return "INSERT INTO player_identity (uuid, name, name_key, last_seen) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, "
                + "name_key = excluded.name_key, last_seen = excluded.last_seen";
    }

    @Override
    protected String describe() {
        return dbFile.getAbsolutePath();
    }
}

package com.liu.liuchat.storage;

import com.liu.liuchat.config.ConfigManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * MySQL 实现：跨服共享数据用，各子服连同一个库，
 * 配合 storage.sync-interval 定时对账即可共享禁言/解禁。
 * 驱动经 plugin.yml 的 libraries 提供。
 */
final class MysqlDatabase extends AbstractJdbcDatabase {

    private final ConfigManager config;

    MysqlDatabase(JavaPlugin plugin, ConfigManager config) {
        super(plugin);
        this.config = config;
    }

    @Override
    protected String jdbcUrl() {
        return config.mysqlUrl();
    }

    @Override
    protected String driverClass() {
        return "com.mysql.cj.jdbc.Driver";
    }

    @Override
    protected String username() {
        return config.mysqlUsername();
    }

    @Override
    protected String password() {
        return config.mysqlPassword();
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
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""";
    }

    @Override
    protected String upsertSql() {
        return """
                INSERT INTO mute (uuid, name, expire_at, reason, operator) VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE name = VALUES(name), expire_at = VALUES(expire_at),
                                        reason = VALUES(reason), operator = VALUES(operator)""";
    }

    protected String upsertProfileSql() {
        return "INSERT INTO chat_profile (owner, nick, color) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE nick = VALUES(nick), color = VALUES(color)";
    }

    /** MySQL UPDATE 子句使用 VALUES()，不需要重复绑定参数。 */
    @Override
    protected String upsertHornSql() {
        return "INSERT INTO horn_balance (owner, credits) VALUES (?, ?) "
                + "ON DUPLICATE KEY UPDATE credits = credits + VALUES(credits)";
    }

    @Override
    protected String describe() {
        return config.mysqlUrl();
    }
}

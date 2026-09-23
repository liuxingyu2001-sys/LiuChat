package com.liu.liuchat.storage;

import com.liu.liuchat.config.ConfigManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 按 storage.type 选实现并完成初始化。
 */
public final class DatabaseFactory {

    private DatabaseFactory() {
    }

    public static Database create(JavaPlugin plugin, ConfigManager config) {
        String type = config.storageType();
        AbstractJdbcDatabase database;
        if (type.equals("mysql")) {
            database = new MysqlDatabase(plugin, config);
        } else {
            if (!type.equals("sqlite")) {
                plugin.getLogger().warning("未知 storage.type: " + type + "，回退为 sqlite");
            }
            database = new SqliteDatabase(plugin);
        }
        database.init();
        return database;
    }
}

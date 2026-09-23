package com.liu.liuchat.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * config.yml 访问层，所有配置项从这里读取。
 */
public final class ConfigManager {

    private final JavaPlugin plugin;
    private FileConfiguration config;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        config = plugin.getConfig();
    }

    public void reload() {
        plugin.reloadConfig();
        config = plugin.getConfig();
    }

    public String server() {
        return config.getString("server", "server");
    }

    public String format() {
        return config.getString("format", "&8[${server}] &e${player}&8» &f${message}");
    }

    public String consoleFormat() {
        return config.getString("console-format", "&8[${server}] &7[控制台] &e${player}&8» &f${message}");
    }

    public boolean crossServerEnabled() {
        return config.getBoolean("cross-server.enable", true);
    }

    // ---------------- 存储 ----------------

    public String storageType() {
        return config.getString("storage.type", "sqlite").trim().toLowerCase();
    }

    /** 与数据库对账刷新禁言缓存的间隔（秒），0 = 关闭 */
    public int syncInterval() {
        return config.getInt("storage.sync-interval", 30);
    }

    public String mysqlUrl() {
        String host = config.getString("storage.mysql.host", "localhost");
        int port = config.getInt("storage.mysql.port", 3306);
        String database = config.getString("storage.mysql.database", "liuchat");
        String properties = config.getString("storage.mysql.properties",
                "useSSL=false&serverTimezone=UTC&characterEncoding=utf8");
        String url = "jdbc:mysql://" + host + ":" + port + "/" + database;
        return properties.isEmpty() ? url : url + "?" + properties;
    }

    public String mysqlUsername() {
        return config.getString("storage.mysql.username", "root");
    }

    public String mysqlPassword() {
        return config.getString("storage.mysql.password", "");
    }

    // ---------------- 重复检测 ----------------

    public int repeatTime() {
        return config.getInt("repeat-check.time", 30);
    }

    public double repeatSimilarity() {
        return config.getDouble("repeat-check.similarity", 80);
    }

    public int repeatMinLength() {
        return config.getInt("repeat-check.min-length", 4);
    }

    /**
     * 聊天冷却秒数，0 = 无冷却。
     * <ul>
     *   <li>拥有 liuchat.cooldown.bypass 直接免除</li>
     *   <li>chat-cooldown 节下除 default 外的键，拥有权限 liuchat.cooldown.&lt;键&gt;
     *       则该权限的值覆盖 default（多个命中取最小 = 最宽松）</li>
     * </ul>
     */
    public int cooldownSeconds(Player player) {
        if (player.hasPermission("liuchat.cooldown.bypass")) {
            return 0;
        }
        Integer override = null;
        ConfigurationSection section = config.getConfigurationSection("chat-cooldown");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                if ("default".equalsIgnoreCase(key)) {
                    continue;
                }
                if (player.hasPermission("liuchat.cooldown." + key)) {
                    int value = section.getInt(key, 0);
                    override = override == null ? value : Math.min(override, value);
                }
            }
        }
        return override != null ? override : config.getInt("chat-cooldown.default", 0);
    }
}

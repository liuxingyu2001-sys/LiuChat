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
        ConfigDefaults.load(plugin, "config.yml");
        plugin.reloadConfig();
        config = plugin.getConfig();
    }

    public void reload() {
        ConfigDefaults.load(plugin, "config.yml");
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

    public boolean aiEnabled() { return config.getBoolean("ai.enable", false); }
    public String aiUrl() { return config.getString("ai.url", ""); }
    public String aiKey() { return config.getString("ai.api-key", ""); }
    public String aiModel() { return config.getString("ai.model", ""); }
    public boolean aiFailOpen() { return config.getBoolean("ai.fail-open", true); }
    public String aiPrompt() {
        return aiReviewPrompt();
    }

    public boolean aiAssistantEnabled() { return config.getBoolean("ai.assistant.enable", false); }
    public String aiAssistantUrl() {
        String url = config.getString("ai.assistant.url", "");
        return url == null || url.isBlank() ? aiUrl() : url;
    }
    public String aiAssistantKey() { return assistantValue(config.getString("ai.assistant.api-key", ""), aiKey()); }
    public String aiAssistantModel() { return assistantValue(config.getString("ai.assistant.model", ""), aiModel()); }

    static String assistantValue(String own, String shared) {
        return own == null || own.isBlank() ? shared : own;
    }
    public String aiAssistantPrompt() {
        return config.getString("ai.assistant.prompt", "你是 Minecraft 服务器聊天助手。简洁回答玩家的问题。不要声称已执行游戏内操作。");
    }
    public java.util.List<String> aiReviewKeywords() { return config.getStringList("ai.review.keywords"); }
    public boolean aiReviewContacts() { return config.getBoolean("ai.review.contacts", true); }
    public boolean aiReviewBlockIps() { return config.getBoolean("ai.review.block-ips", true); }
    public boolean aiReviewBlockDomains() { return config.getBoolean("ai.review.block-domains", true); }
    public boolean aiReviewEnabled() { return config.getBoolean("ai.review.enable", false); }
    public boolean aiReviewManualEnabled() { return config.getBoolean("ai.review.manual-enable", true); }
    public boolean aiReviewPrivate() { return config.getBoolean("ai.review.private-chat", true); }
    public String aiReviewPrompt() {
        return config.getString("ai.review.prompt", "审核 Minecraft 聊天记录，识别违规发言并返回结构化 JSON。");
    }
    public int aiReviewIntervalMinutes() { return Math.max(1, Math.min(1440, config.getInt("ai.review.interval-minutes", 60))); }
    public int aiReviewTimeoutSeconds() { return Math.max(5, Math.min(120, config.getInt("ai.review.timeout-seconds", 90))); }

    public int aiAssistantTimeoutSeconds() {
        return Math.max(5, Math.min(120, config.getInt("ai.assistant.timeout-seconds", 60)));
    }
    public String aiAssistantDefaultName() { return config.getString("ai.assistant.default", "bot").strip(); }
    public java.util.Map<String, String> aiAssistantProfiles() {
        org.bukkit.configuration.ConfigurationSection section = config.getConfigurationSection("ai.assistant.profiles");
        if (section == null) return java.util.Map.of();
        java.util.Map<String, String> profiles = new java.util.LinkedHashMap<>();
        for (String name : section.getKeys(false)) {
            if (name.matches("[a-zA-Z0-9_-]{1,48}")) profiles.put(name, section.getString(name + ".skill", ""));
        }
        return java.util.Map.copyOf(profiles);
    }
    public String aiAssistantDefaultSkill() { return config.getString("ai.assistant.default-skill", "").strip(); }
    public int aiAssistantMaxQuestion() { return Math.max(1, Math.min(2000, config.getInt("ai.assistant.max-question", 300))); }
    public int aiAssistantMaxAnswer() { return Math.max(1, Math.min(4000, config.getInt("ai.assistant.max-answer", 600))); }
    public int aiAssistantDialogWidth() { return Math.max(100, Math.min(800, config.getInt("ai.assistant.dialog-width", 520))); }

    public boolean logEnabled() { return config.getBoolean("chat-log.enable", true); }

    public String logFormat() {
        return config.getString("chat-log.format", "[${time}] [${type}] [${server}] ${player} -> ${target}: ${message}");
    }

    public boolean preferOwnCommands() {
        return config.getBoolean("commands.prefer-liuchat", true);
    }

    public java.util.List<String> hornModes() {
        return config.getStringList("horn.modes");
    }
    public String hornTitle() { return config.getString("horn.title", "&6全服喇叭"); }
    public String hornSound() { return config.getString("horn.sound", "BLOCK_NOTE_BLOCK_BELL"); }
    public int hornDurationTicks() { return Math.max(20, config.getInt("horn.duration-ticks", 100)); }

    public String hornFormat() {
        return config.getString("horn.format", "&6[全服喇叭] &e${player}&7: &f${message}");
    }

    public boolean crossServerEnabled() {
        return config.getBoolean("cross-server.enable", true) && !crossServerSecret().isBlank();
    }

    public String crossServerSecret() {
        return config.getString("cross-server.secret", "").strip();
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

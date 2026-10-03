package com.liu.liuchat.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

/** Fills missing bundled options without overwriting any local values or custom sections. */
public final class ConfigDefaults {
    /** 每服本地文件：不与共享配置同步。 */
    private static final java.util.Set<String> LOCAL_RESOURCES = java.util.Set.of("chat.yml", "ai.yml");

    private ConfigDefaults() { }

    public static YamlConfiguration load(JavaPlugin plugin, String name) {
        File root = plugin instanceof com.liu.liuchat.LiuChat chat && !LOCAL_RESOURCES.contains(name)
                ? chat.getConfigRoot() : plugin.getDataFolder();
        return load(plugin, root, name);
    }

    public static YamlConfiguration load(JavaPlugin plugin, File root, String name) {
        File file = new File(root, name);
        if (!file.exists()) {
            try (InputStream input = plugin.getResource(name)) {
                if (input == null) throw new java.io.IOException("Missing resource: " + name);
                java.nio.file.Files.createDirectories(root.toPath());
                java.nio.file.Files.copy(input, file.toPath());
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // Another server may have initialized the shared file first.
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Cannot initialize " + file, e);
            }
        }
        YamlConfiguration local = new YamlConfiguration();
        local.options().parseComments(true);
        try { local.load(file); }
        catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "读取 " + name + " 失败，保留原文件", e);
            throw new IllegalStateException("Cannot load " + file, e);
        }
        try (InputStream input = plugin.getResource(name)) {
            if (input == null) return local;
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.options().parseComments(true);
            defaults.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            boolean changed = merge(local, defaults, name);
            if (changed) local.save(file);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "补全 " + name + " 配置失败", e);
        }
        return local;
    }

    static boolean merge(YamlConfiguration local, YamlConfiguration defaults) {
        return merge(local, defaults, "");
    }

    /**
     * 把内置默认值补进已有文件，不覆盖用户改过的值；name 用于识别需要一次性升级迁移的文件。
     */
    static boolean merge(YamlConfiguration local, YamlConfiguration defaults, String name) {
        boolean changed = false;
        if ("config.yml".equals(name)) {
            changed |= migrateHornTemplates(local, defaults);
            changed |= migrateChatFilter(local);
        }
        for (String path : defaults.getKeys(true)) {
            Object value = defaults.get(path);
            if (value instanceof ConfigurationSection || local.contains(path, true)) continue;
            local.set(path, value);
            local.setComments(path, defaults.getComments(path));
            local.setInlineComments(path, defaults.getInlineComments(path));
            changed = true;
        }
        return changed;
    }

    /** 旧版只有一个 horn.format，拆成三种显示格式时按它补齐缺失项。 */
    private static boolean migrateHornTemplates(YamlConfiguration local, YamlConfiguration defaults) {
        String legacy = local.getString("horn.format");
        if (legacy == null) {
            return false;
        }
        boolean changed = false;
        for (String key : new String[]{"horn.message-format", "horn.title-message-format",
                "horn.actionbar-message-format"}) {
            if (!local.contains(key, true) && defaults.contains(key, true)) {
                local.set(key, legacy);
                local.setComments(key, defaults.getComments(key));
                local.setInlineComments(key, defaults.getInlineComments(key));
                changed = true;
            }
        }
        return changed;
    }

    /**
     * 旧版屏蔽词在 {@code ai.review} 下；现已拆为共享的 {@code chat-filter}（AI 配置改为每服本地）。
     * 仅在完全没有 chat-filter 段时迁移，避免与用户新配置冲突。
     */
    private static boolean migrateChatFilter(YamlConfiguration local) {
        if (local.contains("chat-filter", true)) {
            return false;
        }
        boolean changed = false;
        changed |= move(local, "ai.enable", "chat-filter.enable");
        changed |= move(local, "ai.review.keywords.match", "chat-filter.keywords.match");
        changed |= move(local, "ai.review.keywords.all", "chat-filter.keywords.all");
        changed |= move(local, "ai.review.contacts", "chat-filter.contacts");
        changed |= move(local, "ai.review.block-ips", "chat-filter.block-ips");
        changed |= move(local, "ai.review.block-domains", "chat-filter.block-domains");
        return changed;
    }

    private static boolean move(YamlConfiguration config, String from, String to) {
        if (!config.contains(from, true)) {
            return false;
        }
        config.set(to, config.get(from));
        return true;
    }
}

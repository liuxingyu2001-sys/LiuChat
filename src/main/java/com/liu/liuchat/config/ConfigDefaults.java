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
    private ConfigDefaults() { }

    public static YamlConfiguration load(JavaPlugin plugin, String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) plugin.saveResource(name, false);
        YamlConfiguration local = new YamlConfiguration();
        local.options().parseComments(true);
        try { local.load(file); }
        catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "读取 " + name + " 失败，保留原文件", e);
            return local;
        }
        try (InputStream input = plugin.getResource(name)) {
            if (input == null) return local;
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.options().parseComments(true);
            defaults.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            boolean changed = merge(local, defaults);
            if (changed) local.save(file);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "补全 " + name + " 配置失败", e);
        }
        return local;
    }

    static boolean merge(YamlConfiguration local, YamlConfiguration defaults) {
        boolean changed = false;
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
}

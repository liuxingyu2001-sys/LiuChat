package com.liu.liuchat.hook;

import com.liu.liuchat.config.ConfigManager;
import com.liu.liuchat.service.MuteService;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * PlaceholderAPI 挂钩。
 * <p>
 * 没装 PAPI 时 {@link #setPlaceholders} 原样返回，不会触发
 * {@code me.clip.*} 类加载，因此类隔离是安全的。
 */
public final class PapiHook {

    private static boolean registered;

    private PapiHook() {
    }

    public static void init(JavaPlugin plugin, ConfigManager config, MuteService muteService) {
        registered = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        if (!registered) {
            return;
        }
        try {
            new LiuChatExpansion(config, muteService, plugin).register();
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "PlaceholderAPI 挂载失败", t);
        }
    }

    public static String setPlaceholders(OfflinePlayer player, String text) {
        if (!registered) {
            return text;
        }
        return PlaceholderAPI.setPlaceholders(player, text);
    }
}

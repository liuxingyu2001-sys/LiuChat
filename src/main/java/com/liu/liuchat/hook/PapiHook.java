package com.liu.liuchat.hook;

import com.liu.liuchat.config.ConfigManager;
import com.liu.liuchat.listener.ChatListener;
import com.liu.liuchat.service.IgnoreService;
import com.liu.liuchat.service.MuteService;
import com.liu.liuchat.service.PlayerProfileService;
import com.liu.liuchat.service.TellService;
import com.liu.liuchat.storage.Database;
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

    public static void init(JavaPlugin plugin, ConfigManager config, MuteService muteService,
                            PlayerProfileService profiles, IgnoreService ignores,
                            TellService tellService, Database database, ChatListener chatListener) {
        registered = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        if (!registered) {
            return;
        }
        try {
            new LiuChatExpansion(config, muteService, profiles, ignores, tellService,
                    database, chatListener, plugin).register();
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

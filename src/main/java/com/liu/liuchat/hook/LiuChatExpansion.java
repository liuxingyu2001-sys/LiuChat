package com.liu.liuchat.hook;

import com.liu.liuchat.config.ConfigManager;
import com.liu.liuchat.model.MuteData;
import com.liu.liuchat.service.MuteService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Optional;

/**
 * %liuchat_xxx% 变量：
 * <ul>
 *   <li>%liuchat_server% —— 子服标识</li>
 *   <li>%liuchat_world% —— 玩家所在世界</li>
 *   <li>%liuchat_muted% —— 是否被禁言 true/false</li>
 *   <li>%liuchat_muted_time% —— 禁言剩余时长（永久/空）</li>
 *   <li>%liuchat_muted_reason% —— 禁言原因（未禁言为空）</li>
 * </ul>
 */
public final class LiuChatExpansion extends PlaceholderExpansion {

    private final ConfigManager config;
    private final MuteService muteService;
    private final String version;

    public LiuChatExpansion(ConfigManager config, MuteService muteService, JavaPlugin plugin) {
        this.config = config;
        this.muteService = muteService;
        this.version = plugin.getDescription().getVersion();
    }

    @Override
    public String getIdentifier() {
        return "liuchat";
    }

    @Override
    public String getAuthor() {
        return "liuxingyu2001";
    }

    @Override
    public String getVersion() {
        return version;
    }

    /** 插件重载后不把本扩展注销掉 */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        if (player == null || params == null) {
            return null;
        }
        String key = params.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "server" -> config.server();
            case "world" -> player.getWorld().getName();
            case "muted", "muted_time", "muted_reason" -> mutedText(player, key);
            default -> null;
        };
    }

    private String mutedText(Player player, String key) {
        Optional<MuteData> muted =
                muteService.check(player.getUniqueId().toString(), player.getName());
        if (muted.isEmpty()) {
            return "";
        }
        MuteData mute = muted.get();
        return switch (key) {
            case "muted" -> "true";
            case "muted_time" -> mute.isPermanent()
                    ? "永久"
                    : com.liu.liuchat.util.TextUtil.formatDuration(
                    mute.expireAt() - System.currentTimeMillis());
            default -> mute.reason() == null ? "" : mute.reason();
        };
    }
}

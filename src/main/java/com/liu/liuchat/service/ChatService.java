package com.liu.liuchat.service;

import com.liu.liuchat.config.ConfigManager;
import com.liu.liuchat.hook.PapiHook;
import com.liu.liuchat.util.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * 聊天消息的格式化与分发。
 * <p>
 * 自己接管分发（取消原事件后逐个发送），不依赖服务器默认聊天 ——
 * 后续屏蔽列表、频道、喇叭等功能都在这里扩展。
 * <p>
 * 颜色处理顺序：模板先翻译 &（含 PAPI 变量），${message} 最后原样插入 ——
 * 没有 liuchat.color 权限的玩家无法通过消息内容注入颜色。
 */
public final class ChatService {

    private final ConfigManager config;

    public ChatService(ConfigManager config) {
        this.config = config;
    }

    public void broadcast(Player player, String rawMessage) {
        String message = player.hasPermission("liuchat.color")
                ? TextUtil.color(rawMessage)
                : rawMessage;
        String playerLine = build(config.format(), player, message);
        String consoleLine = build(config.consoleFormat(), player, message);
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(playerLine);
        }
        Bukkit.getConsoleSender().sendMessage(consoleLine);
    }

    private String build(String template, Player player, String message) {
        String line = template
                .replace("${server}", config.server())
                .replace("${player}", player.getName())
                .replace("${world}", player.getWorld().getName());
        line = PapiHook.setPlaceholders(player, line);
        line = TextUtil.color(line);
        return line.replace("${message}", message);
    }
}

package com.liu.liuchat.service;

import com.liu.liuchat.config.ConfigManager;
import com.liu.liuchat.hook.PapiHook;
import com.liu.liuchat.util.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * 聊天消息的格式化与分发（本服 + 跨服接收）。
 * <p>
 * 自己接管分发（取消原事件后逐个发送），不依赖服务器默认聊天 ——
 * 屏蔽列表、频道、喇叭等功能在这里扩展。
 * <p>
 * 颜色处理顺序：模板先翻译 &（含 PAPI 变量），${message} 最后原样插入 ——
 * 颜色权限（liuchat.color）在监听器侧裁决好、把处理完的文本传进来，
 * 没权限的玩家无法通过消息内容注入颜色，跨服同样遵守发送端的裁决。
 */
public final class ChatService {

    private final ConfigManager config;

    public ChatService(ConfigManager config) {
        this.config = config;
    }

    /**
     * 本服广播。
     *
     * @param message 已按权限处理过颜色的消息文本
     */
    public void broadcast(Player player, String message) {
        String playerLine = build(config.format(),
                config.server(), player.getName(), player.getWorld().getName(), player, message);
        String consoleLine = build(config.consoleFormat(),
                config.server(), player.getName(), player.getWorld().getName(), player, message);
        sendToAll(playerLine, consoleLine);
    }

    /**
     * 跨服消息落地渲染：${server} 显示发送端子服（跨服聊天的意义所在），
     * 玩家不在本服 → ${world} 为 "-" 且跳过 PAPI（没有真实玩家上下文）。
     *
     * @param message 发送端已按其权限处理过的消息文本，原样使用
     */
    public void broadcastRemote(String originServer, String playerName, String message) {
        String playerLine = build(config.format(), originServer, playerName, "-", null, message);
        String consoleLine = build(config.consoleFormat(), originServer, playerName, "-", null, message);
        sendToAll(playerLine, consoleLine);
    }

    private void sendToAll(String playerLine, String consoleLine) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(playerLine);
        }
        Bukkit.getConsoleSender().sendMessage(consoleLine);
    }

    private String build(String template, String server, String playerName, String world,
                         Player papiContext, String message) {
        String line = template
                .replace("${server}", server)
                .replace("${player}", playerName)
                .replace("${world}", world);
        if (papiContext != null) {
            line = PapiHook.setPlaceholders(papiContext, line);
        }
        line = TextUtil.color(line);
        return line.replace("${message}", message);
    }
}

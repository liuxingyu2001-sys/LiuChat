package com.liu.liuchat.service;

import com.liu.liuchat.LiuChat;
import com.liu.liuchat.config.MessageManager;
import com.liu.liuchat.util.Schedulers;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 好友/邮件的玩家通知：本服玩家直接发；其他子服借现有 {@code deliverPrivate}
 * 通过跨服链路送达。离线玩家只落库，上线后再提示。
 */
public final class SocialNotifier {
    private final JavaPlugin plugin;
    private final MessageManager messages;
    private final CrossServerService crossServer;

    public SocialNotifier(JavaPlugin plugin, MessageManager messages, CrossServerService crossServer) {
        this.plugin = plugin;
        this.messages = messages;
        this.crossServer = crossServer;
    }

    /** 按语言键通知在线玩家；跨服私密投递不需要主线程等待。 */
    public void send(String playerName, String key, String... placeholders) {
        if (playerName == null || playerName.isBlank()) return;
        sendRaw(playerName, messages.prefix() + messages.get(key, placeholders));
    }

    public void sendRaw(String playerName, String line) {
        if (playerName == null || playerName.isBlank() || line == null || line.isEmpty()) return;
        Player local = Bukkit.getPlayerExact(playerName);
        if (local != null) {
            Schedulers.runFor(plugin, local, () -> local.sendMessage(line));
            return;
        }
        if (crossServer == null || !crossServer.isEnabled()) return;
        LiuChat chat = LiuChat.instance();
        if (chat == null || !plugin.isEnabled()) return;
        Schedulers.runGlobal(plugin, () -> {
            try {
                chat.deliverPrivate(null, playerName, TextComponent.fromLegacyText(line));
            } catch (RuntimeException error) {
                plugin.getLogger().log(java.util.logging.Level.FINE, "跨服社交通知发送失败", error);
            }
        });
    }

    /** 给本服在线玩家发一条已带颜色/前缀的消息（命令反馈用）。 */
    public void local(Player player, String line) {
        if (player == null || line == null) return;
        Schedulers.runFor(plugin, player, () -> player.sendMessage(line));
    }
}

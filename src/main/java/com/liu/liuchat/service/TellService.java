package com.liu.liuchat.service;

import com.liu.liuchat.config.MessageManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 私聊服务：本服直达 + 跨服投递。
 * <p>
 * 跨服链路（目标不在本服时）：
 * <ol>
 *   <li>先给发送者本地回显；</li>
 *   <li>TELL 广播到其他子服，只有目标在线的那个服会投递，并回 TELL_ACK
 *       （定向送回本服）；</li>
 *   <li>收到回执 → 完成；超时未收到 → 说明目标不在线（或所在服不可达），
 *       给发送者补一条"消息未送达"——避免消息静默丢失。</li>
 * </ol>
 * 消息文本的颜色由发送端裁决（liuchat.color），跨服传输的是处理完的文本，接收端原样插入。
 */
public final class TellService {

    /** 回执超时：本服 → 代理 → 目标服 → 代理 → 本服，本地网络毫秒级，3 秒非常宽裕 */
    private static final long ACK_TIMEOUT_TICKS = 60L;

    private final JavaPlugin plugin;
    private final MessageManager messages;
    private final CrossServerService crossServer;
    private IgnoreService ignores;
    private ChatLogService logs;
    private com.liu.liuchat.config.ConfigManager config;
    private ChatPresentation presentation;
    private ItemShowcase items;
    private PlayerProfileService profiles;
    public void setPresentation(ChatPresentation presentation, PlayerProfileService profiles, ItemShowcase items) {
        this.presentation = presentation;
        this.profiles = profiles;
        this.items = items;
    }
    public void setChatLogService(ChatLogService logs) { this.logs = logs; }
    public void setConfig(com.liu.liuchat.config.ConfigManager config) { this.config = config; }
    /** key = msgId；只存发送者标识与目标名，回执到达即移除 */
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private record Pending(String senderUuid, String targetName) {
    }

    public TellService(JavaPlugin plugin, MessageManager messages, CrossServerService crossServer) {
        this.plugin = plugin;
        this.messages = messages;
        this.crossServer = crossServer;
    }

    public void setIgnoreService(IgnoreService ignores) { this.ignores = ignores; }

    /** 本服直达：双方都在线，立即送达，不走网络 */
    public void deliverLocal(Player from, Player target, String text) {
        if (ignores != null && ignores.ignores(target, from.getUniqueId().toString(), from.getName())) {
            messages.send(from, "tell.offline", "${player}", target.getName());
            return;
        }
        String message = prepareMessage(from, text);
        String itemData = snapshotItem(from, message);
        if (hasItemToken(message) && itemData.isEmpty()) message = presentation.itemUnavailable(message);
        String itemId = registerItem(from.getName(), from.getUniqueId().toString(), itemData);
        String resolved = presentation != null && presentation.privateEnabled()
                ? presentation.snapshotPlaceholders(from, message) : "";
        privateMessage(from, true, config.server(), from.getName(), from.getUniqueId().toString(),
                from.getWorld().getName(), target.getName(), from, message, itemId, resolved, nickname(from));
        privateMessage(target, false, config.server(), from.getName(), from.getUniqueId().toString(),
                from.getWorld().getName(), target.getName(), from, message, itemId, resolved, nickname(from));
        if (logs != null) {
            logs.local("TELL", from.getName(), target.getName(), message);
            if (config != null) logs.recordPrivate(from.getUniqueId().toString(), from.getName(), target.getName(), message);
        }
    }

    /**
     * 目标不在本服：跨服链路；跨服功能关闭时按离线处理。
     */
    public void sendCross(Player sender, String targetName, String text) {
        if (!crossServer.isEnabled()) {
            messages.send(sender, "tell.offline", "${player}", targetName);
            return;
        }
        String message = prepareMessage(sender, text);
        String itemData = snapshotItem(sender, message);
        if (hasItemToken(message) && itemData.isEmpty()) message = presentation.itemUnavailable(message);
        String msgId = UUID.randomUUID().toString();
        pending.put(msgId, new Pending(sender.getUniqueId().toString(), targetName));

        // 回执只决定后续要不要补未送达提示。
        String placeholders = presentation != null && presentation.privateEnabled()
                ? presentation.snapshotPlaceholders(sender, message) : "";
        if (!crossServer.publishTell(sender, msgId, sender.getName(), targetName, message,
                placeholders, nickname(sender), itemData)) {
            pending.remove(msgId);
            messages.send(sender, "tell.cross-offline", "${player}", targetName);
            return;
        }
        String itemId = registerItem(sender.getName(), sender.getUniqueId().toString(), itemData);
        privateMessage(sender, true, config.server(), sender.getName(), sender.getUniqueId().toString(),
                sender.getWorld().getName(), targetName, sender, message, itemId, placeholders, nickname(sender));
        if (logs != null) {
            logs.local("TELL", sender.getName(), targetName, message);
            if (config != null) logs.recordPrivate(sender.getUniqueId().toString(), sender.getName(), targetName, message);
        }

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (pending.remove(msgId) == null) {
                return; // 回执已到，送达成功
            }
            Player stillOnline = Bukkit.getPlayer(sender.getUniqueId());
            if (stillOnline != null) {
                messages.send(stillOnline, "tell.cross-offline", "${player}", targetName);
            }
        }, ACK_TIMEOUT_TICKS);
    }

    /**
     * 本服收到 TELL：目标恰好在线则投递并回执；
     * 不在本服则什么都不做（等目标所在那个服处理）。
     */
    public void onNetworkTell(CrossServerCodec.Inbound.TellMessage tell) {
        Player target = Bukkit.getPlayerExact(tell.targetName());
        if (target == null || ignores != null && ignores.ignores(target, "", tell.senderName())) {
            return;
        }
        // 发送端已裁决颜色，原样插入
        String itemId = registerItem(tell.senderName(), tell.uuid(), tell.itemData());
        String message = itemId == null && hasItemToken(tell.message())
                ? presentation.itemUnavailable(tell.message()) : tell.message();
        privateMessage(target, false, tell.originServer(), tell.senderName(), tell.uuid(), tell.world(),
                target.getName(), null, message, itemId, tell.placeholders(), tell.nick());
        if (logs != null) logs.record("TELL", tell.originServer(), tell.senderName(), target.getName(), tell.message());
        crossServer.publishTellAck(target, tell.msgId(), tell.originServer());
    }

    private String prepareMessage(Player player, String text) {
        return com.liu.liuchat.util.ColorParser.playerText(text, player.hasPermission("liuchat.color"));
    }

    private boolean hasItemToken(String message) {
        return presentation != null && presentation.privateEnabled() && presentation.itemEnabled()
                && message.contains(presentation.itemToken());
    }

    private String snapshotItem(Player sender, String message) {
        return hasItemToken(message) && items != null ? items.snapshot(sender) : "";
    }

    private String registerItem(String owner, String uuid, String data) {
        return items == null ? null : items.register(owner, uuid, data);
    }

    private String nickname(Player player) {
        if (profiles == null) return player.getName();
        var profile = profiles.get(player);
        return profile == null || profile.nick().isBlank() ? player.getName() : profile.nick();
    }

    private void privateMessage(Player recipient, boolean outgoing, String server, String senderName, String uuid,
                                String world, String targetName, Player sender, String message,
                                String itemId, String placeholders, String nick) {
        if (presentation == null || !presentation.privateEnabled()) {
            messages.send(recipient, outgoing ? "tell.to-tell" : "tell.from", "${player}",
                    outgoing ? targetName : senderName, "${message}", message);
            return;
        }
        var parts = presentation.renderPrivate(outgoing, server, senderName, uuid,
                world, targetName, sender, message, itemId, placeholders, nick);
        recipient.sendMessage(PaperChatComponents.convert(parts, items));
    }

    /** 收到回执：移除待确认记录，超时任务自然跳过 */
    public void onAck(String msgId) {
        pending.remove(msgId);
    }
}

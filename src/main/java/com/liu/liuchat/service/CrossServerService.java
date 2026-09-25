package com.liu.liuchat.service;

import com.liu.liuchat.config.ConfigManager;
import com.liu.liuchat.model.MuteData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.logging.Level;

/**
 * 跨服消息收发：BungeeCord plugin messaging（BungeeCord / Velocity 均原生支持）。
 * <ul>
 *   <li>发送：经在线玩家连接 Forward 给代理，代理转发（ALL 或定向到具体子服）</li>
 *   <li>接收：按 {@link CrossServerCodec.Inbound} 类型分流 ——
 *       CHAT 落地渲染，TELL/TELL_ACK 交给 {@link TellService}</li>
 *   <li>防回环：代理的 Forward ALL 不含发送端子服；再以 server 名兜底丢弃</li>
 *   <li>单服/无代理：转发无人接收，静默丢弃，开着无副作用</li>
 * </ul>
 * 注意：本类是 {@link PluginMessageListener}（messenger 注册），不是 Bukkit Listener。
 */
public final class CrossServerService implements PluginMessageListener, Listener {

    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final ChatService chatService;
    /** 构造后注入（TellService 又要经本类发包，避免构造器循环依赖） */
    private TellService tellService;
    private MuteService muteService;
    private boolean enabled;
    private boolean warned;
    private final RemotePlayers remotePlayers = new RemotePlayers();
    private BukkitTask presenceTask;

    public CrossServerService(JavaPlugin plugin, ConfigManager config, ChatService chatService) {
        this.plugin = plugin;
        this.config = config;
        this.chatService = chatService;
        CrossServerCodec.setSharedSecret(config.crossServerSecret());
        this.enabled = config.crossServerEnabled();
        if (!enabled) {
            return;
        }
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CrossServerCodec.BUNGEE_CHANNEL);
        // Bukkit 会将旧名和 namespaced 名规范化为同一通道，注册一次即可。
        plugin.getServer().getMessenger().registerIncomingPluginChannel(
                plugin, CrossServerCodec.BUNGEE_CHANNEL, this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        presenceTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::publishPresence, 1200L, 1200L);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            publishPresence();
            sendViaAny(() -> CrossServerCodec.encodePresenceRequest(config.server()));
        }, 20L);
    }

    public void setTellService(TellService tellService) {
        this.tellService = tellService;
    }

    public void setMuteService(MuteService muteService) { this.muteService = muteService; }

    public void publishMute(MuteData mute) {
        if (!enabled) return;
        sendViaAny(() -> CrossServerCodec.encodeMute(mute.uuid(), mute.name(), mute.expireAt(),
                mute.reason() == null ? "" : mute.reason(), mute.operator() == null ? "" : mute.operator()));
    }

    public void publishUnmute(String uuid) {
        if (enabled) sendViaAny(() -> CrossServerCodec.encodeUnmute(uuid));
    }

    public void publishItemAnnouncement(Player sender, String template, String snapshot) {
        if (!enabled) return;
        try {
            fire(sender, CrossServerCodec.encodeItemAnnouncement(config.server(), sender.getUniqueId().toString(),
                    sender.getName(), template, snapshot));
        } catch (Exception e) { warnOnce(e); }
    }

    public void publishAnnouncement(Player carrier, net.md_5.bungee.api.chat.BaseComponent[] components) {
        if (!enabled) return;
        try {
            fire(carrier, CrossServerCodec.encodeAnnouncement(config.server(),
                    net.md_5.bungee.chat.ComponentSerializer.toString(components)));
        } catch (Exception e) { warnOnce(e); }
    }

    public void publishHorn(Player player, String message) {
        if (!enabled) return;
        try {
            fire(player, CrossServerCodec.encodeHorn(config.server(), player.getUniqueId().toString(),
                    player.getName(), message));
        } catch (Exception e) { warnOnce(e); }
    }

    private interface Packet { byte[] encode() throws java.io.IOException; }
    private void sendViaAny(Packet packet) {
        Player carrier = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (carrier == null) return; // Proxy messaging needs a player; database polling is the fallback.
        try { fire(carrier, packet.encode()); } catch (Exception e) { warnOnce(e); }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public List<String> completePlayers(String prefix) {
        List<String> local = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        return remotePlayers.complete(prefix, local, System.currentTimeMillis());
    }

    /** 本服 + 其他子服在线玩家 ID，供聊天 @ 提及识别（含跨服玩家）。 */
    public List<String> knownPlayerNames() {
        List<String> names = new java.util.ArrayList<>(remotePlayers.names(System.currentTimeMillis()));
        for (Player online : Bukkit.getOnlinePlayers()) names.add(online.getName());
        return names;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled) return;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!event.getPlayer().isOnline()) return;
            publishPresence();
            sendViaAny(() -> CrossServerCodec.encodePresenceRequest(config.server()));
        }, 2L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (!enabled) return;
        try {
            fire(event.getPlayer(), CrossServerCodec.encodePresenceQuit(config.server(), event.getPlayer().getName()));
        } catch (Exception e) { warnOnce(e); }
    }

    private void publishPresence() {
        if (!enabled) return;
        List<String> names = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        for (int i = 0; i < names.size(); i += CrossServerCodec.MAX_PRESENCE_NAMES) {
            List<String> batch = names.subList(i, Math.min(i + CrossServerCodec.MAX_PRESENCE_NAMES, names.size()));
            sendViaAny(() -> CrossServerCodec.encodePresence(config.server(), batch));
        }
    }

    // ---------------- 发送 ----------------

    /** 群聊广播到其他子服。message 必须已按权限处理过颜色（发送端裁决） */
    public void publishChat(Player sender, String message, String itemData, String placeholders, String nick) {
        if (!enabled) {
            return;
        }
        try {
            fire(sender, CrossServerCodec.encodeChat(
                    config.server(),
                    sender.getUniqueId().toString(),
                    sender.getName(),
                    message, itemData, placeholders, nick));
        } catch (Exception e) {
            warnOnce(e);
            if (!itemData.isEmpty()) {
                try {
                    fire(sender, CrossServerCodec.encodeChat(config.server(),
                            sender.getUniqueId().toString(), sender.getName(), message, "", placeholders, nick));
                } catch (Exception fallbackError) {
                    warnOnce(fallbackError);
                }
            }
        }
    }

    /** 以虚拟身份（公屏 AI 等）广播到其他子服；没有发送者连接时借任意在线玩家转发。 */
    public void publishChatAs(String server, String uuid, String name,
                              String message, String itemData, String placeholders, String nick) {
        if (!enabled) return;
        sendViaAny(() -> CrossServerCodec.encodeChat(server, uuid, name, message, itemData, placeholders, nick));
    }

    /** 跨服私聊：广播给其他子服，只有目标所在服会投递并回执 */
    public boolean publishTell(Player via, String msgId, String senderName, String targetName, String message,
                               String placeholders, String nick, String itemData) {
        if (!enabled) {
            return false;
        }
        try {
            fire(via, CrossServerCodec.encodeTell(msgId, config.server(), senderName, targetName, message,
                    via.getUniqueId().toString(), via.getWorld().getName(), placeholders, nick, itemData));
            return true;
        } catch (Exception e) {
            warnOnce(e);
            return false;
        }
    }

    /** 私聊回执：定向送回发送端子服（mode = 对方子服名） */
    public void publishTellAck(Player via, String msgId, String replyToServer) {
        if (!enabled) {
            return;
        }
        try {
            fire(via, CrossServerCodec.encodeTellAck(msgId, config.server(), replyToServer));
        } catch (Exception e) {
            warnOnce(e);
        }
    }

    /** 经该玩家连接发给代理；只发送一次，避免多人在线时重复转发。 */
    private void fire(Player via, byte[] packet) {
        via.sendPluginMessage(plugin, CrossServerCodec.BUNGEE_CHANNEL, packet);
    }

    // ---------------- 接收 ----------------

    @Override
    public void onPluginMessageReceived(String channel, Player source, byte[] message) {
        if (!enabled || !isIncomingChannel(channel)) {
            return;
        }
        CrossServerCodec.Inbound inbound = CrossServerCodec.decodeInbound(message);
        if (inbound == null) {
            // 同通道上还有其它插件（GetServer、白名单同步等）的消息，忽略
            return;
        }
        switch (inbound) {
            case CrossServerCodec.Inbound.ChatMessage chat -> {
                // 回环/子服同名保护
                if (chat.originServer().equals(config.server())) {
                    return;
                }
                chatService.broadcastRemote(chat.originServer(), chat.uuid(),
                        chat.playerName(), chat.message(), chat.itemData(), chat.placeholders(), chat.nick());
            }
            case CrossServerCodec.Inbound.TellMessage tell -> {
                if (tell.originServer().equals(config.server())) {
                    return;
                }
                if (tellService != null) {
                    tellService.onNetworkTell(tell);
                }
            }
            case CrossServerCodec.Inbound.ItemAnnouncement announcement -> {
                if (!announcement.origin().equals(config.server()))
                    chatService.broadcastItemAnnouncement(announcement.origin(), announcement.name(),
                            announcement.uuid(), announcement.template(), announcement.snapshot());
            }
            case CrossServerCodec.Inbound.Announcement announcement -> {
                if (announcement.origin().equals(config.server())) return;
                try {
                    chatService.broadcastAnnouncement(
                            net.md_5.bungee.chat.ComponentSerializer.parse(announcement.componentsJson()));
                } catch (RuntimeException e) { warnOnce(e); }
            }
            case CrossServerCodec.Inbound.Horn horn -> {
                if (!horn.origin().equals(config.server()))
                    chatService.hornRemote(horn.origin(), horn.uuid(), horn.name(), horn.message());
            }
            case CrossServerCodec.Inbound.Mute mute -> {
                if (muteService != null) muteService.applyRemote(new MuteData(
                        mute.uuid(), mute.name(), mute.expires(), mute.reason(), mute.operator()));
            }
            case CrossServerCodec.Inbound.Unmute unmute -> {
                if (muteService != null) muteService.removeRemote(unmute.uuid());
            }
            case CrossServerCodec.Inbound.TellAck ack -> {
                if (tellService != null) {
                    tellService.onAck(ack.msgId());
                }
            }
            case CrossServerCodec.Inbound.Presence presence -> {
                if (!presence.server().equals(config.server()))
                    remotePlayers.update(presence.server(), presence.names(), System.currentTimeMillis());
            }
            case CrossServerCodec.Inbound.PresenceQuit quit -> {
                if (!quit.server().equals(config.server())) remotePlayers.remove(quit.server(), quit.name());
            }
            case CrossServerCodec.Inbound.PresenceRequest request -> {
                if (!request.server().equals(config.server())) publishPresence();
            }
        }
    }

    private static boolean isIncomingChannel(String channel) {
        for (String candidate : CrossServerCodec.INCOMING_CHANNELS) {
            if (candidate.equals(channel)) {
                return true;
            }
        }
        return false;
    }

    private void warnOnce(Exception e) {
        // 只警告一次，避免刷屏
        if (!warned) {
            warned = true;
            plugin.getLogger().log(Level.WARNING, "跨服消息发送失败，后续失败不再提示", e);
        }
    }

    public void close() {
        if (!enabled) {
            return;
        }
        enabled = false;
        if (presenceTask != null) presenceTask.cancel();
        org.bukkit.event.HandlerList.unregisterAll(this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CrossServerCodec.BUNGEE_CHANNEL);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(
                plugin, CrossServerCodec.BUNGEE_CHANNEL, this);
    }
}

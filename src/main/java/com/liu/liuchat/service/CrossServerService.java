package com.liu.liuchat.service;

import com.liu.liuchat.config.ConfigManager;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.logging.Level;

/**
 * 跨服聊天：BungeeCord plugin messaging（BungeeCord / Velocity 均原生支持）。
 * <ul>
 *   <li>发送：本服玩家发言后，经其连接把消息 Forward 给代理，代理转发给其他子服</li>
 *   <li>接收：解析后交 {@link ChatService#broadcastRemote} 按本服格式渲染</li>
 *   <li>防回环：代理的 Forward ALL 不含发送端子服；再以 server 名兜底丢弃</li>
 *   <li>单服/无代理：转发无人接收，静默丢弃，开着无副作用</li>
 * </ul>
 * 注意：本类是 {@link PluginMessageListener}（messenger 注册），不是 Bukkit Listener。
 */
public final class CrossServerService implements PluginMessageListener {

    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final ChatService chatService;
    private boolean enabled;
    private boolean warned;

    public CrossServerService(JavaPlugin plugin, ConfigManager config, ChatService chatService) {
        this.plugin = plugin;
        this.config = config;
        this.chatService = chatService;
        this.enabled = config.crossServerEnabled();
        if (!enabled) {
            return;
        }
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CrossServerCodec.BUNGEE_CHANNEL);
        // 双名注册，原因见 CrossServerCodec.INCOMING_CHANNELS 注释
        for (String channel : CrossServerCodec.INCOMING_CHANNELS) {
            plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, channel, this);
        }
    }

    /**
     * 把本服发言转发到其他子服。message 必须是<b>已按权限处理过颜色</b>的文本
     * ——权限由发送端裁决，接收端不重复判断。
     * <p>
     * 从异步聊天线程调用安全：走玩家 netty 连接发包，线程安全；
     * 只经发言者本人连接发一次（Server#sendPluginMessage 会广播给所有玩家导致重复）。
     */
    public void publish(Player sender, String message) {
        if (!enabled) {
            return;
        }
        try {
            byte[] packet = CrossServerCodec.encodeForward(
                    config.server(),
                    sender.getUniqueId().toString(),
                    sender.getName(),
                    message);
            sender.sendPluginMessage(plugin, CrossServerCodec.BUNGEE_CHANNEL, packet);
        } catch (Exception e) {
            // 只警告一次，避免异步刷屏
            if (!warned) {
                warned = true;
                plugin.getLogger().log(Level.WARNING, "跨服消息发送失败，后续失败不再提示", e);
            }
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player source, byte[] message) {
        if (!enabled || !isIncomingChannel(channel)) {
            return;
        }
        CrossServerCodec.Decoded decoded = CrossServerCodec.decodeInbound(message);
        if (decoded == null) {
            // 同通道上还有其它插件（GetServer、白名单同步等）的消息，忽略
            return;
        }
        if (decoded.originServer().equals(config.server())) {
            // 回环/子服同名保护
            return;
        }
        chatService.broadcastRemote(decoded.originServer(), decoded.playerName(), decoded.message());
    }

    private static boolean isIncomingChannel(String channel) {
        for (String candidate : CrossServerCodec.INCOMING_CHANNELS) {
            if (candidate.equals(channel)) {
                return true;
            }
        }
        return false;
    }

    public void close() {
        if (!enabled) {
            return;
        }
        enabled = false;
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CrossServerCodec.BUNGEE_CHANNEL);
        for (String channel : CrossServerCodec.INCOMING_CHANNELS) {
            plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, channel, this);
        }
    }
}

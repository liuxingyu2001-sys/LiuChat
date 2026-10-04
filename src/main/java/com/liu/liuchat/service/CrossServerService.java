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
 * 跨服消息收发：两条可选链路，协议载荷共用 {@link CrossServerCodec}。
 * <ul>
 *   <li>transport=proxy（默认）：BungeeCord plugin messaging（BungeeCord / Velocity 均原生支持）</li>
 *   <li>transport=redis：{@link RedisBus} pub/sub 直连，发布失败自动回落代理；
 *       空服也能收发、发送不依赖在线玩家载体；两条链路接收侧都监听，
 *       汇合到 handleFrame 同一分发口；Redis 自回环靠 origin 校验与 MUTE/UNMUTE 幂等</li>
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
    private final RemotePlayers remotePlayers = new RemotePlayers();
    private BukkitTask presenceTask;
    /** Redis 传输层（transport=redis 时创建）；null = 纯代理模式，行为与旧版完全一致 */
    private RedisBus redis;
    /** 当前 Redis 连接参数指纹，/lc reload 时判断要不要重连。 */
    private String redisKey;
    /** 协议失败告警去重（跨服收到解析不了的 LiuChat 包时提示）。 */
    private long lastProtocolFailures;
    private long lastProtocolReportAt;

    public CrossServerService(JavaPlugin plugin, ConfigManager config, ChatService chatService) {
        this.plugin = plugin;
        this.config = config;
        this.chatService = chatService;
        CrossServerCodec.setSharedSecret(config.crossServerSecret());
        if (config.crossServerEnabled()) {
            start();
        }
    }

    /** 注册通道与事件、起 Redis 与名单广播。构造时与 /lc reload 打开开关时都会走这里。 */
    private void start() {
        if (enabled) return;
        CrossServerCodec.setSharedSecret(config.crossServerSecret());
        String server = config.server();
        if (server == null || server.isBlank() || "server".equals(server)) {
            // 两个子服共用默认名时，收发两端的 originServer 互相相等，
            // 防回环判断会把对方消息全当本服发的丢掉 —— 表现为「跨服没反应」且零日志
            plugin.getLogger().warning("跨服已开启但 server: 未配置（当前为 '"
                    + server + "'）。两个子服同名会互相丢弃对方消息，跨服将表现为无反应！请在 config.yml 设置唯一子服名。");
        }
        enabled = true;
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CrossServerCodec.BUNGEE_CHANNEL);
        // Bukkit 会将旧名和 namespaced 名规范化为同一通道，注册一次即可。
        plugin.getServer().getMessenger().registerIncomingPluginChannel(
                plugin, CrossServerCodec.BUNGEE_CHANNEL, this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        openRedis();
        presenceTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::publishPresence, 1200L, 1200L);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            publishPresence();
            sendViaAny(() -> CrossServerCodec.encodePresenceRequest(config.server()));
        }, 20L);
        plugin.getLogger().info("跨服已开启（server=" + config.server()
                + "，传输=" + (redis != null ? "redis+代理回落" : "proxy") + "）");
    }

    private String redisKey() {
        return config.redisHost() + ":" + config.redisPort() + ":" + config.redisDb()
                + ":" + config.redisChannel() + ":" + config.redisPassword();
    }

    /** 打开 Redis 传输（transport=redis 时）。 */
    private void openRedis() {
        if (redis != null || !config.crossServerRedisTransport()) return;
        redisKey = redisKey();
        redis = new RedisBus(new RedisBus.Settings(config.redisHost(), config.redisPort(),
                config.redisPassword(), config.redisDb(), config.redisChannel()),
                this::onRedisFrame, plugin.getLogger());
        redis.start();
        plugin.getLogger().info("跨服传输: redis（发布失败自动回落代理转发）");
    }

    /**
     * 重载跨服配置：密钥、enable 开关、Redis 连接参数都在 {@code /lc reload} 生效。
     * <p>
     * 原实现三者都只在构造器读一次 —— 改了密钥必须重启；而改密钥后忘重启的那台服
     * 签出来的包会被所有对端验签拒绝并<b>静默丢弃</b>。
     */
    public void reload() {
        CrossServerCodec.setSharedSecret(config.crossServerSecret());
        boolean want = config.crossServerEnabled();
        if (want && !enabled) {
            start();
            return;
        }
        if (!want && enabled) {
            close();
            plugin.getLogger().info("跨服已按配置关闭");
            return;
        }
        if (enabled) {
            if (redis != null && !redisKey().equals(redisKey)) {
                redis.close();
                redis = null;
                plugin.getLogger().info("Redis 跨服传输已按新配置重连");
            }
            openRedis();
            // 密钥可能已变，立刻同步一次名单方便当场验证链路
            publishPresence();
        }
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
            send(CrossServerCodec.encodeItemAnnouncement(config.server(), sender.getUniqueId().toString(),
                    sender.getName(), template, snapshot), sender);
        } catch (Exception e) { warnOnce(e); }
    }

    public void publishAnnouncement(Player carrier, net.md_5.bungee.api.chat.BaseComponent[] components) {
        if (!enabled) return;
        try {
            send(CrossServerCodec.encodeAnnouncement(config.server(),
                    net.md_5.bungee.chat.ComponentSerializer.toString(components)), carrier);
        } catch (Exception e) { warnOnce(e); }
    }

    public void validateHorn(Player player, String message) throws java.io.IOException {
        CrossServerCodec.encodeHorn(config.server(), player.getUniqueId().toString(), player.getName(), message);
    }

    public void publishHorn(Player player, String message) {
        if (!enabled) return;
        try {
            send(CrossServerCodec.encodeHorn(config.server(), player.getUniqueId().toString(),
                    player.getName(), message), player);
        } catch (Exception e) { warnOnce(e); }
    }

    private interface Packet { byte[] encode() throws java.io.IOException; }

    /**
     * 统一发送入口：Redis（transport=redis 且可用）优先——不需在线玩家载体，空服也能收；
     * 未启用或发布失败则回落代理转发（借在线玩家当载体）。
     * 每条消息只走一条链路，接收端两条链路都监听，天然无重复投递。
     * 异常原样抛给调用方，各调用点保留原有的降级与告警逻辑。
     */
    private boolean send(byte[] forwardPacket, Player carrierHint) {
        if (redis != null) {
            byte[] frame = CrossServerCodec.stripForward(forwardPacket);
            if (frame != null && redis.publish(frame)) return true;
        }
        Player carrier = carrierHint != null ? carrierHint
                : Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        // 代理消息需要玩家载体；没有载体时禁言等靠数据库对账兜底
        if (carrier == null) return false;
        fire(carrier, forwardPacket);
        return true;
    }

    private void sendViaAny(Packet packet) {
        try {
            send(packet.encode(), null);
        } catch (Exception e) {
            warnOnce(e);
        }
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
            // 下线通知同样走统一入口：Redis 优先，空服对端也能收到
            send(CrossServerCodec.encodePresenceQuit(config.server(), event.getPlayer().getName()),
                    event.getPlayer());
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
            send(CrossServerCodec.encodeChat(
                    config.server(),
                    sender.getUniqueId().toString(),
                    sender.getName(),
                    message, itemData, placeholders, nick), sender);
        } catch (Exception e) {
            warnOnce(e);
            if (!itemData.isEmpty()) {
                try {
                    send(CrossServerCodec.encodeChat(config.server(),
                            sender.getUniqueId().toString(), sender.getName(), message, "", placeholders, nick), sender);
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
            send(CrossServerCodec.encodeTell(msgId, config.server(), senderName, targetName, message,
                    via.getUniqueId().toString(), via.getWorld().getName(), placeholders, nick, itemData), via);
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
            send(CrossServerCodec.encodeTellAck(msgId, config.server(), replyToServer), via);
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
        handleFrame(message);
    }

    /** Redis 订阅线程回调：切回主线程再分发（与代理链路同一路口，会触碰 Bukkit API）。 */
    private void onRedisFrame(byte[] frame) {
        if (!enabled) return;
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (enabled) handleFrame(frame);
            });
        } catch (RuntimeException e) {
            // 停服过程中调度器已关闭：丢弃本帧，不影响代理链路
            if (enabled) plugin.getLogger().log(Level.FINE, "Redis 帧调度失败", e);
        }
    }

    /**
     * 解码并按类型分发——代理与 Redis 两条链路的公共入口，均在主线程执行。
     * Redis 会把本服自己发布的包也回传进来，
     * 各类型的 origin 校验 + MUTE/UNMUTE 幂等已覆盖自回环。
     */
    private void handleFrame(byte[] message) {
        CrossServerCodec.Inbound inbound = CrossServerCodec.decodeInbound(message);
        if (inbound == null) {
            // 同通道上还有其它插件（GetServer、白名单同步等）的消息，忽略 —— 属正常；
            // 但「标签是我们的、负载解不开」是真故障（密钥不一致/协议版本不符），必须能看出来
            reportProtocolFailure();
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

    /** 协议解析失败的限流告警（代理与 Redis 两条链路共用）。 */
    private void reportProtocolFailure() {
        long failures = CrossServerCodec.protocolFailures();
        if (failures == lastProtocolFailures
                || System.currentTimeMillis() - lastProtocolReportAt <= WARN_INTERVAL_MILLIS) {
            return;
        }
        lastProtocolFailures = failures;
        lastProtocolReportAt = System.currentTimeMillis();
        plugin.getLogger().warning("跨服收到 " + failures + " 条无法解析的 LiuChat 包"
                + "（常见原因：各子服 cross-server.secret 不一致，或 LiuChat 版本/协议不同）");
    }

    /** 同类告警的最小间隔：原实现是「第一次之后永久静默」，
     *  导致第一次故障之后无论再出什么错都看不到日志，跨服断了都没人知道。 */
    private static final long WARN_INTERVAL_MILLIS = 60_000L;
    private long lastWarnAt;

    private void warnOnce(Exception e) {
        long now = System.currentTimeMillis();
        if (now - lastWarnAt < WARN_INTERVAL_MILLIS) return;
        lastWarnAt = now;
        plugin.getLogger().log(Level.WARNING, "跨服消息发送失败（60 秒内不重复提示）", e);
    }

    public void close() {
        if (!enabled) {
            return;
        }
        enabled = false;
        if (redis != null) {
            redis.close(); // 先停 Redis，避免回调在注销期间继续调度
            redis = null;
        }
        if (presenceTask != null) presenceTask.cancel();
        org.bukkit.event.HandlerList.unregisterAll(this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CrossServerCodec.BUNGEE_CHANNEL);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(
                plugin, CrossServerCodec.BUNGEE_CHANNEL, this);
    }
}

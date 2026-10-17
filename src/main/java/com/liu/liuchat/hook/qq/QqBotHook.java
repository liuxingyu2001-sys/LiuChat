package com.liu.liuchat.hook.qq;

import com.liu.liuchat.config.ConfigDefaults;
import com.liu.liuchat.config.ConfigManager;
import com.liu.liuchat.service.ChatService;
import com.liu.liuchat.service.CrossServerService;
import com.liu.liuchat.util.Schedulers;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * QQ 官方机器人与游戏公屏的双向桥接。
 *
 * <p>方向一（QQ -> 游戏）：WebSocket 回调线程收到事件后，切回全局线程，以固定格式
 * 广播给在线玩家（走忽略列表与聊天日志），可选同步到其他子服。
 *
 * <p>方向二（游戏 -> QQ）：玩家公屏发言通过一个有界队列交给独立发送线程，
 * 按 {@code send.interval-ms} 限速；若窗口内刚收到过 QQ 消息，会附带 msg_id
 * 作为官方允许的被动回复，否则才尝试主动消息。队列满时丢弃并限频告警，
 * 绝不在主线程等待网络。
 */
public final class QqBotHook implements AutoCloseable {

    private static final int MAX_QUEUE = 256;
    private static final long QUEUE_WARN_INTERVAL_MS = 60_000L;

    private final JavaPlugin plugin;
    private final ChatService chatService;
    private final CrossServerService crossServer;
    private final ConfigManager config;
    private final BlockingQueue<Outbound> outbound = new LinkedBlockingQueue<>(MAX_QUEUE);
    private final Map<String, ReplyContext> replies = new ConcurrentHashMap<>();
    private final Set<String> seenTargets = ConcurrentHashMap.newKeySet();
    /** QQ 网关 resume 可能重放事件，按 event id 做短时去重。 */
    private final Map<String, Long> recentEvents = new java.util.LinkedHashMap<>();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "LiuChat-QQ-Sender");
        thread.setDaemon(true);
        return thread;
    });

    private volatile QqBotSettings settings;
    private volatile QqBotClient client;
    private volatile String clientFingerprint = "";
    private volatile boolean closed;
    private volatile long lastQueueWarningAt;

    public QqBotHook(JavaPlugin plugin, ChatService chatService, CrossServerService crossServer,
                     ConfigManager config) {
        this.plugin = plugin;
        this.chatService = chatService;
        this.crossServer = crossServer;
        this.config = config;
    }

    /** 启动发送队列并读取 qq.yml；未启用时只加载配置，不建立连接。 */
    public void start() {
        if (closed) return;
        try {
            sender.execute(this::sendLoop);
        } catch (RuntimeException ignored) {
            // 插件停用竞态：发送线程已关闭
        }
        reload();
    }

    /**
     * 重新读取 qq.yml。只有连接参数变化才重建 WebSocket；只改格式/目标时不断线。
     */
    public synchronized void reload() {
        if (closed) return;
        QqBotSettings next = QqBotSettings.load(ConfigDefaults.load(plugin, "qq.yml"));
        settings = next;
        if (!next.usable()) {
            boolean wasConnected = client != null;
            closeClient();
            if (next.enabled()) {
                plugin.getLogger().warning("QQ 机器人已开启，但 app-id 或 app-secret 为空，未连接");
            } else if (wasConnected) {
                plugin.getLogger().info("QQ 机器人已关闭");
            }
            return;
        }
        if (client != null && next.fingerprint().equals(clientFingerprint)) {
            return;
        }
        closeClient();
        try {
            QqBotClient created = new QqBotClient(next, this::onEvent, plugin.getLogger());
            client = created;
            clientFingerprint = next.fingerprint();
            created.start();
            plugin.getLogger().info("QQ 机器人已启用（app-id=" + next.appId()
                    + "，环境=" + (next.sandbox() ? "沙箱" : "正式") + "，intents=0x"
                    + Integer.toHexString(next.intents()) + "）");
        } catch (RuntimeException error) {
            closeClient();
            plugin.getLogger().log(Level.WARNING, "QQ 机器人启动失败，已停用本次连接", error);
        }
    }

    /** 当前状态，供日志/调试；不触发网络。 */
    public String status() {
        if (closed) return "stopped";
        QqBotClient current = client;
        if (current != null) return current.status();
        QqBotSettings snapshot = settings;
        return snapshot != null && snapshot.usable() ? "starting" : "disabled";
    }

    // ---------------- 游戏 -> QQ ----------------

    /** 玩家公屏发言后调用；只入队，不做网络 IO。 */
    public void publishPlayerChat(Player player, String message) {
        QqBotSettings snapshot = settings;
        if (closed || player == null || message == null || message.isBlank()) return;
        if (snapshot == null || !snapshot.usable() || !snapshot.sendEnabled()
                || snapshot.sendTargets().isEmpty()) return;
        String content = QqBotText.outgoingLine(snapshot.sendFormat(), config.server(),
                player.getName(), message, snapshot.sendMaxLength());
        if (content.isBlank()) return;
        for (QqTarget target : snapshot.sendTargets()) {
            if (!outbound.offer(new Outbound(target, content))) {
                warnQueueFull();
            }
        }
    }

    private void warnQueueFull() {
        long now = System.currentTimeMillis();
        if (now - lastQueueWarningAt < QUEUE_WARN_INTERVAL_MS) return;
        lastQueueWarningAt = now;
        plugin.getLogger().warning("QQ 发送队列已满，丢弃消息；请检查 QQ 机器人网络/频率限制");
    }

    private void sendLoop() {
        while (!closed && !Thread.currentThread().isInterrupted()) {
            Outbound task;
            try {
                task = outbound.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
            if (task == null) continue;
            QqBotClient current = client;
            QqBotSettings snapshot = settings;
            if (current == null || snapshot == null || !snapshot.usable() || !snapshot.sendEnabled()) {
                continue;
            }
            boolean passive = false;
            String msgId = null;
            int msgSeq = 0;
            if (snapshot.passiveReply()) {
                ReplyContext reply = replies.get(task.target().key());
                long window = snapshot.passiveWindowSeconds() * 1000L;
                if (reply != null && window > 0 && System.currentTimeMillis() - reply.at() <= window) {
                    passive = true;
                    msgId = reply.msgId();
                    msgSeq = reply.nextSeq();
                }
            }
            QqBotClient.SendResult result = current.send(task.target(), task.content(), msgId, msgSeq);
            long pause = snapshot.sendIntervalMs();
            if (!result.ok() && result.retryAfterMs() > 0) {
                pause = Math.max(pause, result.retryAfterMs());
            }
            if (passive && !result.ok() && result.status() == 0) {
                // 网络失败时不重试，避免被动回复与主动消息各发一遍造成重复
                plugin.getLogger().log(Level.FINE, "QQ 被动回复失败: " + result.detail());
            }
            if (pause > 0) {
                try {
                    Thread.sleep(pause);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    // ---------------- QQ -> 游戏 ----------------

    private void onEvent(QqBotEvent event) {
        QqBotSettings snapshot = settings;
        if (closed || snapshot == null || !snapshot.usable() || !snapshot.receiveEnabled()) return;
        if (!snapshot.receiveType(event.type())) return;
        if (!snapshot.receiveChannels().isEmpty()
                && !snapshot.receiveChannels().contains(event.targetId())) return;
        String content = QqBotText.cleanIncoming(event.content(), snapshot.receiveMaxLength());
        if (content.isEmpty()) return;
        if (!firstEvent(event.eventId())) return;
        String key = event.target().key();
        rememberReply(key, event.eventId());
        if (snapshot.logTargets() && seenTargets.add(key)) {
            plugin.getLogger().info("QQ 会话首次收到消息: " + key
                    + (snapshot.sendTargets().isEmpty() ? "（可填入 qq.yml 的 send.targets）" : ""));
        }
        Schedulers.runGlobal(plugin, () -> deliver(snapshot, event, content));
    }

    private void deliver(QqBotSettings snapshot, QqBotEvent event, String content) {
        if (closed || !plugin.isEnabled()) return;
        try {
            String senderName = event.senderName();
            senderName = senderName == null || senderName.isBlank()
                    ? QqBotText.fallbackSender(snapshot.fallbackName(), event.senderId())
                    : QqBotText.stripSection(senderName);
            String source = event.type().label();
            String identity = event.senderId() == null || event.senderId().isBlank()
                    ? event.targetId() : event.senderId();
            String uuid = UUID.nameUUIDFromBytes(("LiuChatQQ:" + event.type().key() + ":" + identity)
                    .getBytes(StandardCharsets.UTF_8)).toString();
            chatService.broadcastExternal(uuid, senderName, snapshot.receiveFormat(), content,
                    source, event.targetId(), snapshot.receiveLog());
            if (snapshot.receiveCrossServer() && crossServer != null) {
                crossServer.publishExternalChat(config.server(), uuid, senderName,
                        snapshot.receiveFormat(), content, source, event.targetId());
            }
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "QQ 消息投递失败", error);
        }
    }

    private void rememberReply(String targetKey, String eventId) {
        if (eventId == null || eventId.isBlank()) return;
        long now = System.currentTimeMillis();
        replies.put(targetKey, new ReplyContext(eventId, now));
        if (replies.size() > 1024) {
            replies.entrySet().removeIf(entry -> now - entry.getValue().at() > 600_000L);
        }
    }

    /** 最多 1024 条 / 2 分钟；重复事件直接丢弃，避免 resume 重放刷屏。 */
    private synchronized boolean firstEvent(String eventId) {
        if (eventId == null || eventId.isBlank()) return true;
        long now = System.currentTimeMillis();
        recentEvents.values().removeIf(seenAt -> now - seenAt > 120_000L);
        if (recentEvents.containsKey(eventId)) return false;
        if (recentEvents.size() >= 1024) {
            recentEvents.remove(recentEvents.keySet().iterator().next());
        }
        recentEvents.put(eventId, now);
        return true;
    }

    // ---------------- 生命周期 ----------------

    private void closeClient() {
        QqBotClient current = client;
        client = null;
        clientFingerprint = "";
        replies.clear();
        synchronized (this) {
            recentEvents.clear();
        }
        outbound.clear();
        if (current != null) current.close();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        outbound.clear();
        sender.shutdownNow();
        closeClient();
    }

    private record Outbound(QqTarget target, String content) { }

    private static final class ReplyContext {
        private final String msgId;
        private final long at;
        private final AtomicInteger seq = new AtomicInteger(1);

        private ReplyContext(String msgId, long at) {
            this.msgId = msgId;
            this.at = at;
        }

        private String msgId() {
            return msgId;
        }

        private long at() {
            return at;
        }

        private int nextSeq() {
            return seq.getAndIncrement();
        }
    }
}

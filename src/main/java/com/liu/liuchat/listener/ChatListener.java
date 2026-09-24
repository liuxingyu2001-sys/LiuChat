package com.liu.liuchat.listener;

import com.liu.liuchat.config.ConfigManager;
import com.liu.liuchat.config.MessageManager;
import com.liu.liuchat.model.MuteData;
import com.liu.liuchat.service.HourlyChatAudit;
import com.liu.liuchat.service.ChatReviewPolicy;
import com.liu.liuchat.service.ChatService;
import com.liu.liuchat.service.CrossServerService;
import com.liu.liuchat.service.MuteService;
import com.liu.liuchat.util.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天主监听器：禁言 → 冷却 → 重复检测 → 本服分发 + 跨服转发。
 * 运行在异步聊天线程，所有集合都用并发安全的。
 */
public final class ChatListener implements Listener {

    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final MessageManager messages;
    private final MuteService muteService;
    private final ChatService chatService;
    private final CrossServerService crossServer;
    private final HourlyChatAudit audit;

    /** 每人最近一次发言时间（冷却用） */
    private final Map<UUID, Long> lastChatAt = new ConcurrentHashMap<>();
    /** 每人最近一次发言内容（重复检测用） */
    private final Map<UUID, LastSaid> lastSaid = new ConcurrentHashMap<>();

    private record LastSaid(long time, String text) {
    }

    public ChatListener(JavaPlugin plugin, ConfigManager config, MessageManager messages,
                        MuteService muteService, ChatService chatService,
                        CrossServerService crossServer, HourlyChatAudit audit) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.muteService = muteService;
        this.chatService = chatService;
        this.crossServer = crossServer;
        this.audit = audit;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        // Cancel before handing off: Bukkit/PAPI/plugin messaging must run on the server thread.
        event.setCancelled(true);
        UUID uuid = event.getPlayer().getUniqueId();
        String text = event.getMessage();
        if (event.isAsynchronous()) {
            Bukkit.getScheduler().runTask(plugin, () -> processChat(uuid, text));
        } else {
            processChat(uuid, text);
        }
    }

    private void processChat(UUID uuid, String text) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return;
        }
        long now = System.currentTimeMillis();

        // 1. 禁言
        Optional<MuteData> muted = muteService.check(uuid.toString(), player.getName());
        if (muted.isPresent()) {
            MuteData mute = muted.get();
            messages.send(player, "chat.muted",
                    "${time}", messages.muteTimeText(mute),
                    "${reason}", mute.reason() == null ? "" : mute.reason());
            return;
        }

        // 2. 冷却
        int cooldown = config.cooldownSeconds(player);
        if (cooldown > 0) {
            long last = lastChatAt.getOrDefault(uuid, 0L);
            long waitMs = last + cooldown * 1000L - now;
            if (waitMs > 0) {
                long seconds = (waitMs + 999) / 1000;
                messages.send(player, "chat.cooldown", "${time}", String.valueOf(seconds));
                return;
            }
        }

        // 3. 重复/相似发言
        if (isSpam(player, text, now)) {
            return;
        }

        if (config.aiEnabled() && ChatReviewPolicy.blocked(text, config.aiReviewKeywords(),
                config.aiReviewContacts(), config.aiReviewBlockIps(), config.aiReviewBlockDomains())) {
            messages.send(player, "ai.local-blocked");
            return;
        }
        lastChatAt.put(uuid, now);
        lastSaid.put(uuid, new LastSaid(now, text));

        String message = player.hasPermission("liuchat.color")
                ? com.liu.liuchat.util.ColorParser.playerText(text)
                : text.replace('§', '&');

        ChatService.Dispatch dispatch = chatService.broadcast(player, message);
        audit.record(uuid.toString(), player.getName(), text);
        crossServer.publishChat(player, dispatch.message(), dispatch.itemData(),
                dispatch.placeholders(), dispatch.nick());
    }

    /**
     * 窗口期内与上次发言完全相同或相似度达标则判为刷屏。
     * 完全相同不受 min-length 限制；相似度比较要求长度达标。
     */
    private boolean isSpam(Player player, String message, long now) {
        int window = config.repeatTime();
        if (window <= 0) {
            return false;
        }
        LastSaid prev = lastSaid.get(player.getUniqueId());
        if (prev == null || now - prev.time() > window * 1000L) {
            return false;
        }
        String current = message.trim();
        String previous = prev.text().trim();
        if (current.isEmpty()) {
            return false;
        }
        boolean spam;
        if (current.equals(previous)) {
            spam = true;
        } else if (current.length() >= config.repeatMinLength()) {
            spam = TextUtil.similarity(previous, current) >= config.repeatSimilarity();
        } else {
            spam = false;
        }
        if (spam) {
            messages.send(player, "chat.repeat");
        }
        return spam;
    }

    /** 退出时清掉该玩家的临时状态；禁言缓存是全局的，不动 */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastChatAt.remove(uuid);
        lastSaid.remove(uuid);
    }
}

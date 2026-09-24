package com.liu.liuchat.listener;

import com.liu.liuchat.config.ConfigManager;
import com.liu.liuchat.config.MessageManager;
import com.liu.liuchat.model.MuteData;
import com.liu.liuchat.service.HourlyChatAudit;
import com.liu.liuchat.service.ChatReviewPolicy;
import com.liu.liuchat.service.ChatService;
import com.liu.liuchat.service.PublicChatAiService;
import com.liu.liuchat.service.CrossServerService;
import com.liu.liuchat.service.MuteService;
import com.liu.liuchat.util.RepeatCheck;
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
    /** 重复/相似发言检测（含「上次发言」记录） */
    private final RepeatCheck repeats = new RepeatCheck();
    /** 公屏 AI 聊天（可选）：玩家发言交给它判定是否点名了 AI */
    private PublicChatAiService publicAi;

    public void setPublicChatAi(PublicChatAiService ai) { this.publicAi = ai; }

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

        // 3. 重复/相似发言；[i] 物品展示跳过（每次展示的物品可能不同，同文案不算刷屏）
        if (repeats.spam(uuid, text, now, config.repeatTime(), config.repeatSimilarity(),
                config.repeatMinLength(), chatService.isItemShow(text))) {
            messages.send(player, "chat.repeat");
            return;
        }

        if (config.aiEnabled() && ChatReviewPolicy.blocked(text, config.aiReviewKeywords(),
                config.aiReviewContacts(), config.aiReviewBlockIps(), config.aiReviewBlockDomains())
                && !player.hasPermission("liuchat.moderation.bypass")) {
            String shown = com.liu.liuchat.util.ColorParser.playerText(text,
                    player.hasPermission("liuchat.color"));
            chatService.sendOwnChat(player, shown);
            notifyModerators(player, text);
            return;
        }
        lastChatAt.put(uuid, now);

        String message = com.liu.liuchat.util.ColorParser.playerText(text,
                player.hasPermission("liuchat.color"));

        ChatService.Dispatch dispatch = chatService.broadcast(player, message);
        audit.record(uuid.toString(), player.getName(), text);
        crossServer.publishChat(player, dispatch.message(), dispatch.itemData(),
                dispatch.placeholders(), dispatch.nick());
        if (publicAi != null) publicAi.onLocalMessage(uuid.toString(), player.getName(), text);
    }

    private void notifyModerators(Player sender, String text) {
        String notice = messages.get("ai.local-blocked-notify", "${player}", sender.getName(), "${message}", text);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("liuchat.moderation.notify")) online.sendMessage(notice);
        }
    }
    /** 退出时清掉该玩家的临时状态；禁言缓存是全局的，不动 */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastChatAt.remove(uuid);
        repeats.clear(uuid);
    }
}

package com.liu.liuchat.service;

import com.liu.liuchat.config.ConfigManager;
import com.liu.liuchat.util.AiChatTriggers;
import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 公屏 AI 聊天：AI 像普通玩家一样参与公共聊天。
 *
 * <p>玩家在公屏点名（{@code @AI} 或消息里提到它的名字）时，AI 以固定虚拟身份把回答
 * 发进公屏，走与玩家完全相同的渲染管线（提及高亮、忽略列表、聊天日志、跨服广播都生效）。
 * 最近的公屏消息作为聊天氛围上下文一起送给模型。所有回调在主线程。
 */
public final class PublicChatAiService {

    /** 公屏请求的会话键：同时只处理一个公屏问题 */
    private static final String PENDING_KEY = "liuchat:public-chat";

    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final AiAssistantService assistant;
    private final ChatService chatService;
    private final CrossServerService crossServer;

    /** 最近公屏消息（含 AI 自己的发言），作为聊天氛围上下文 */
    private final Deque<String> recent = new ArrayDeque<>();
    private long lastReplyAt;

    public PublicChatAiService(JavaPlugin plugin, ConfigManager config, AiAssistantService assistant,
                               ChatService chatService, CrossServerService crossServer) {
        this.plugin = plugin;
        this.config = config;
        this.assistant = assistant;
        this.chatService = chatService;
        this.crossServer = crossServer;
    }

    /** 本服玩家公屏发言（主线程）。 */
    public void onLocalMessage(String uuid, String name, String message) {
        onMessage(uuid, name, message, false);
    }

    /** 其他子服的公屏发言（主线程）。 */
    public void onRemoteMessage(String uuid, String name, String message) {
        onMessage(uuid, name, message, true);
    }

    private void onMessage(String uuid, String name, String message, boolean remote) {
        if (!config.aiChatEnabled() || message == null || message.isBlank() || !plugin.isEnabled()) return;
        String aiName = config.aiChatName();
        if (aiName.isEmpty() || uuid.equals(aiUuid().toString()) || name.equalsIgnoreCase(aiName)) return;
        List<String> context = new ArrayList<>(recent);
        remember(name, message);
        if (remote && !config.aiChatRespondRemote()) {
            return; // 跨服消息默认只进上下文：多台服都开 AI 时避免同时抢答
        }
        if (!AiChatTriggers.triggers(message, aiName)) {
            double chance = config.aiChatChance();
            if (chance <= 0 || ThreadLocalRandom.current().nextDouble() >= chance) return;
        }
        long now = System.currentTimeMillis();
        if (now - lastReplyAt < config.aiChatCooldownSeconds() * 1000L) return;
        String question = AiChatTriggers.composeQuestion(context, name, message,
                aiName, config.aiAssistantMaxQuestion());
        // BUSY / UNAVAILABLE 等一律静默：AI 不该为失败刷屏。
        // 会话单独放在 "<助手>#public" 上：公屏群聊的上下文不和 /lc ask 的私有会话混在一起
        assistant.askKeyed(PENDING_KEY, config.aiChatAssistant(), config.aiChatAssistant() + "#public",
                question, this::onAnswer);
    }

    private void onAnswer(AiAssistantService.Result result) {
        if (result.status() != AiAssistantService.Status.OK) return;
        sendReply(result.answer());
    }

    /** AI 回复进公屏：与玩家发言同一条管线（本地渲染 + 跨服广播）。 */
    public void sendReply(String raw) {
        String reply = clean(raw);
        if (reply.isEmpty() || !plugin.isEnabled()) return;
        String aiName = config.aiChatName();
        String uuid = aiUuid().toString();
        remember(aiName, reply);
        lastReplyAt = System.currentTimeMillis();
        chatService.broadcastPlain(uuid, aiName, formatLine(config.aiChatFormat(), aiName, reply), reply);
        crossServer.publishChatAs(config.server(), uuid, aiName, reply, "", "", aiName);
    }

    /** 跨服来的这条发言是否是公屏 AI：固定格式渲染，不走聊天格式节点/变量解析。 */
    public boolean isAiSender(String uuid, String name) {
        String aiName = config.aiChatName();
        return !aiName.isEmpty()
                && (aiUuid().toString().equals(uuid) || aiName.equalsIgnoreCase(name));
    }

    /**
     * 固定聊天格式：只替换 ${player} = AI 名字、${message} = 回复内容。
     * 假人拿不到玩家上下文，PAPI 等其他插件占位符解析不出来，所以一律不解析变量；
     * & 颜色码只对格式做所见即所得的直译（不合并、不美化），回复内容原样显示。
     */
    public static String formatLine(String format, String aiName, String message) {
        String template = format == null || format.isBlank() ? "&7[AI] &b${player}&7: &f${message}" : format;
        return ChatColor.translateAlternateColorCodes('&', template.replace("${player}", aiName == null ? "" : aiName))
                .replace("${message}", message == null ? "" : message);
    }

    /** 公屏发言一行说完：换行压成空格、颜色码转字面、按 max-answer 截断。 */
    private String clean(String answer) {
        String value = answer.replace('\n', ' ').replace('\r', ' ').replace('§', '&').trim();
        value = value.replaceAll("\\s{2,}", " ");
        int max = config.aiChatMaxAnswer();
        return value.length() > max ? value.substring(0, max) : value;
    }

    private void remember(String speaker, String message) {
        int limit = config.aiChatContextMessages();
        if (limit <= 0) return;
        recent.addLast(speaker + ": " + message.replaceAll("(?i)§.", ""));
        while (recent.size() > limit) recent.removeFirst();
    }

    /** AI 的固定虚拟 UUID：名字派生、跨服稳定，忽略列表/头像插件都能持续识别它。 */
    public UUID aiUuid() {
        return UUID.nameUUIDFromBytes(("LiuChatAI:" + config.aiChatName()).getBytes(StandardCharsets.UTF_8));
    }
}

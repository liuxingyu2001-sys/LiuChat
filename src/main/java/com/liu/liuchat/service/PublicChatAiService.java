package com.liu.liuchat.service;

import com.liu.liuchat.config.ConfigManager;
import com.liu.liuchat.util.AiChatTriggers;
import com.liu.liuchat.util.Mentions;
import com.liu.liuchat.util.TextUtil;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
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

    /** 固定聊天格式的缺省值（与 config.yml / ConfigManager 一致） */
    private static final String DEFAULT_FORMAT = "&7[AI] &b${player}&7: &f${message}";

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
        String visible = AiChatTriggers.visibleText(message);
        if (!config.aiChatEnabled() || visible == null || visible.isBlank() || !plugin.isEnabled()) return;
        String aiName = config.aiChatName();
        if (aiName.isEmpty() || uuid.equals(aiUuid().toString()) || name.equalsIgnoreCase(aiName)) return;
        List<String> context = new ArrayList<>(recent);
        remember(name, visible);
        if (remote && !config.aiChatRespondRemote()) {
            return; // 跨服消息默认只进上下文：多台服都开 AI 时避免同时抢答
        }
        if (!AiChatTriggers.triggers(visible, aiName)) {
            double chance = config.aiChatChance();
            if (chance <= 0 || ThreadLocalRandom.current().nextDouble() >= chance) return;
        }
        long now = System.currentTimeMillis();
        if (now - lastReplyAt < config.aiChatCooldownSeconds() * 1000L) return;
        String question = AiChatTriggers.composeQuestion(context, name, visible,
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
        String format = config.aiChatFormat();
        UUID headUuid = aiHeadUuid();
        chatService.broadcastPlain(uuid, aiName,
                formatComponents(format, aiName, reply, headUuid),
                formatLine(format, aiName, reply), reply);
        crossServer.publishChatAs(config.server(), uuid, aiName, reply, "",
                AiChatSnapshot.encode(format, headUuid), aiName);
    }

    /** ${head} 头像解析用的 UUID：配置 head-uuid（真实皮肤）优先，否则用 AI 虚拟 UUID。 */
    public UUID aiHeadUuid() {
        String value = config.aiChatHeadUuid();
        if (!value.isEmpty()) {
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException ignored) {
                // 配置写错时回退虚拟 UUID，不阻断聊天
            }
        }
        return aiUuid();
    }

    /** 跨服来的这条发言是否是公屏 AI：固定格式渲染，不走聊天格式节点/变量解析。 */
    public boolean isAiSender(String uuid, String name) {
        String aiName = config.aiChatName();
        return !aiName.isEmpty() && aiName.equalsIgnoreCase(name)
                && aiUuid(aiName).toString().equals(uuid);
    }

    /**
     * 固定聊天格式（纯文本）：只替换 ${player} = AI 名字、${message} = 回复内容，${head} 去掉。
     * 假人拿不到玩家上下文，PAPI 等其他插件占位符解析不出来，所以变量一律不解析；
     * 回复内容最后插入、原样显示（内容里的 & 、占位符、MiniMessage 标签都不解析）。
     * 供控制台/聊天日志等纯文本场景使用。
     */
    public static String formatLine(String format, String aiName, String message) {
        String template = format == null || format.isBlank() ? DEFAULT_FORMAT : format;
        return TextUtil.color(template.replace("${player}", aiName == null ? "" : aiName)
                .replace("${head}", ""))
                .replace("${message}", message == null ? "" : message);
    }

    /**
     * 固定聊天格式（聊天组件）：同 {@link #formatLine}，另把 {@code ${head}} 解析成皮肤头像
     * （与普通聊天 ${head} 相同的 liuchat-head 机制）。回复里的 {@code ${head}} 原样显示。
     */
    public static BaseComponent[] formatComponents(String format, String aiName, String message, UUID headUuid) {
        String template = format == null || format.isBlank() ? DEFAULT_FORMAT : format;
        String colored = TextUtil.color(template.replace("${player}", aiName == null ? "" : aiName));
        String body = message == null ? "" : message;
        java.util.List<BaseComponent> out = new java.util.ArrayList<>();
        int pos = 0;
        int index;
        while ((index = colored.indexOf("${head}", pos)) >= 0) {
            String prefix = pos == 0 ? "" : Mentions.state(colored.substring(0, pos));
            addLegacy(out, prefix + colored.substring(pos, index).replace("${message}", body));
            TextComponent head = new TextComponent("");
            if (headUuid != null) head.setInsertion("liuchat-head:" + headUuid);
            out.add(head);
            pos = index + "${head}".length();
        }
        String prefix = pos == 0 ? "" : Mentions.state(colored.substring(0, pos));
        addLegacy(out, prefix + colored.substring(pos).replace("${message}", body));
        return out.toArray(new BaseComponent[0]);
    }

    private static void addLegacy(java.util.List<BaseComponent> out, String text) {
        if (text.isEmpty()) return;
        java.util.Collections.addAll(out, TextComponent.fromLegacyText(text));
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
        recent.addLast(speaker + ": " + message);
        while (recent.size() > limit) recent.removeFirst();
    }

    /** AI 的固定虚拟 UUID：名字派生、跨服稳定，忽略列表/头像插件都能持续识别它。 */
    public UUID aiUuid() {
        return aiUuid(config.aiChatName());
    }

    static UUID aiUuid(String name) {
        return UUID.nameUUIDFromBytes(("LiuChatAI:" + name).getBytes(StandardCharsets.UTF_8));
    }
}

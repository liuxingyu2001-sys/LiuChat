package com.liu.liuchat.service;

import com.liu.liuchat.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Shared private assistant for commands and Citizens dialogs. All callbacks run on the main thread.
 * <p>
 * 会话按助手名共享：多个 NPC 绑到同一助手 = 同一份上下文，任何玩家的提问都会保留，
 * 超过 ai.assistant.history-* 上限后从最旧开始丢。system 提示词保持逐字节稳定以便服务商前缀缓存。
 */
public final class AiAssistantService {
    public enum Status { OK, UNAVAILABLE, UNKNOWN_ASSISTANT, UNKNOWN_SKILL, TOO_LONG, BUSY, FAILED }
    public record Result(Status status, String answer) { }

    /** 答案缓存条数上限；key 含上下文指纹，命中 = 0 token */
    private static final int CACHE_ENTRIES = 256;
    /** 缓存与会话里保留的原始回答长度上限（展示会被 max-answer 再截一次，不影响展示结果） */
    private static final int MAX_STORED_ANSWER = 4000;

    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final AiClient client;
    private final AiSkillService skills;
    private final AiSessionStore sessions;
    private final AiAnswerCache cache = new AiAnswerCache(CACHE_ENTRIES);
    private final Set<String> pending = new HashSet<>();

    public AiAssistantService(JavaPlugin plugin, ConfigManager config, AiClient client, AiSkillService skills,
                              AiSessionStore sessions) {
        this.plugin = plugin;
        this.config = config;
        this.client = client;
        this.skills = skills;
        this.sessions = sessions;
    }

    public Status ask(Player player, String assistant, String question, Consumer<Result> onResult) {
        UUID uuid = player.getUniqueId();
        return askKeyed(uuid.toString(), assistant, assistant, question, result -> {
            // 玩家等待期间退出/重进后不再回调，避免旧请求落到新会话
            if (Bukkit.getPlayer(uuid) == player) onResult.accept(result);
        });
    }

    /** 会话就用助手名的便捷重载（/lc ask、NPC 对话）。 */
    public Status askKeyed(String pendingKey, String assistant, String question, Consumer<Result> onResult) {
        return askKeyed(pendingKey, assistant, assistant, question, onResult);
    }

    /**
     * 以任意会话键提问：公屏 AI 聊天等没有提问者玩家的场景也接在同一套助手上。
     *
     * @param session 会话键（与助手名解耦，公屏群聊可单独一份上下文）
     */
    public Status askKeyed(String pendingKey, String assistant, String session, String question,
                           Consumer<Result> onResult) {
        if (!config.aiAssistantEnabled() || config.aiAssistantUrl().isBlank() || config.aiAssistantModel().isBlank())
            return Status.UNAVAILABLE;
        Map<String, String> profiles = config.aiAssistantProfiles();
        String skill = profiles.isEmpty() ? config.aiAssistantDefaultSkill() : profiles.get(assistant);
        if (skill != null && skill.isEmpty() && assistant.equals(config.aiAssistantDefaultName())
                && !config.aiAssistantDefaultSkill().isEmpty()) skill = config.aiAssistantDefaultSkill();
        if (skill == null) return Status.UNKNOWN_ASSISTANT;
        String instructions = skill.isEmpty() ? "" : skills.content(skill);
        if (!skill.isEmpty() && instructions == null) return Status.UNKNOWN_SKILL;
        if (question.isBlank() || question.length() > config.aiAssistantMaxQuestion()) return Status.TOO_LONG;
        if (!pending.add(pendingKey)) return Status.BUSY;
        String prompt = config.aiAssistantPrompt();
        if (instructions != null && !instructions.isEmpty())
            prompt += "\n\nAssistant: " + assistant + "\nSkill: " + skill + "\n" + instructions;
        String key = config.aiAssistantKey().isBlank() ? System.getenv("LIUCHAT_AI_API_KEY") : config.aiAssistantKey();
        int timeout = config.aiAssistantTimeoutSeconds();
        String url = config.aiAssistantUrl();
        String model = config.aiAssistantModel();
        final String selectedSkill = skill;
        final int promptLength = prompt.length();

        AiSessionStore.Limits limits = new AiSessionStore.Limits(config.aiAssistantHistoryMessages(),
                config.aiAssistantHistoryChars(), config.aiAssistantHistorySeconds());
        List<AiClient.Msg> history = limits.enabled() ? sessions.context(session, limits) : List.of();
        // 缓存 key = 配置指纹 + 上下文指纹 + 问题：换模型/改提示词/会话推进都会自动失效
        String cacheKey = AiAnswerCache.sha256(url + '\n' + model + '\n' + prompt)
                + '\n' + AiAnswerCache.fingerprint(history) + '\n' + question;
        long ttlMillis = config.aiAssistantCacheSeconds() * 1000L;
        if (ttlMillis > 0) {
            String cached = cache.get(cacheKey, System.currentTimeMillis());
            if (cached != null) {
                // 命中也照样入会话，后续玩家的上下文才是完整的
                if (limits.enabled()) sessions.finish(sessions.open(session, limits, question), cached, limits);
                String display = clean(cached, config.aiAssistantMaxAnswer());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    pending.remove(pendingKey); // 缓存命中不走网络，也必须在这里解锁，否则该会话永久 BUSY
                    if (!plugin.isEnabled()) return;
                    onResult.accept(new Result(Status.OK, display));
                });
                return Status.OK;
            }
        }

        AiSessionStore.Turn turn = limits.enabled() ? sessions.open(session, limits, question) : null;
        client.complete(url, key, model, prompt, history, question, timeout, config.aiAssistantMaxTokens())
                .whenComplete((answer, error) -> {
                    if (!plugin.isEnabled()) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (error == null) {
                            // 玩家在等待期间退出也要留下这轮问答，共享会话不因个人进出而丢数据
                            if (turn != null) sessions.finish(turn, answer, limits);
                            if (ttlMillis > 0) cache.put(cacheKey, store(answer), ttlMillis, System.currentTimeMillis());
                        } else if (turn != null) {
                            sessions.fail(turn);
                        }
                        pending.remove(pendingKey);
                        if (error != null) {
                            plugin.getLogger().warning("AI 聊天助手请求失败 (skill=" + selectedSkill
                                    + ", 提示词字符数=" + promptLength + ", 上下文字符数=" + historyChars(history)
                                    + ", 超时=" + timeout + "秒): " + error);
                            onResult.accept(new Result(Status.FAILED, ""));
                            return;
                        }
                        onResult.accept(new Result(Status.OK, clean(answer, config.aiAssistantMaxAnswer())));
                    });
                });
        return Status.OK;
    }

    /**
     * 统一清洗：统一换行符（换行要原样留到显示，多段回答靠它分段）、颜色符号转义、
     * 按 max-answer 截断（截断前的部分才花过 token）。
     */
    private static String clean(String answer, int maxAnswer) {
        String value = answer.replace("\r\n", "\n").replace('\r', '\n').replace('§', '&');
        return value.substring(0, Math.min(value.length(), maxAnswer));
    }

    private static int historyChars(List<AiClient.Msg> history) {
        int total = 0;
        for (AiClient.Msg msg : history) total += msg.content().length();
        return total;
    }

    private static String store(String answer) {
        return answer.length() > MAX_STORED_ANSWER ? answer.substring(0, MAX_STORED_ANSWER) : answer;
    }
}

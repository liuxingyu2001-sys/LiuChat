package com.liu.liuchat.service;

import com.liu.liuchat.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Shared private assistant for commands and Citizens dialogs. All callbacks run on the main thread. */
public final class AiAssistantService {
    public enum Status { OK, UNAVAILABLE, UNKNOWN_ASSISTANT, UNKNOWN_SKILL, TOO_LONG, BUSY, FAILED }
    public record Result(Status status, String answer) { }

    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final AiClient client;
    private final AiSkillService skills;
    private final Set<UUID> pending = new HashSet<>();

    public AiAssistantService(JavaPlugin plugin, ConfigManager config, AiClient client, AiSkillService skills) {
        this.plugin = plugin;
        this.config = config;
        this.client = client;
        this.skills = skills;
    }

    public Status ask(Player player, String assistant, String question, Consumer<Result> onResult) {
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
        UUID uuid = player.getUniqueId();
        if (!pending.add(uuid)) return Status.BUSY;
        String prompt = config.aiAssistantPrompt();
        if (instructions != null && !instructions.isEmpty())
            prompt += "\n\nAssistant: " + assistant + "\nSkill: " + skill + "\n" + instructions;
        String key = config.aiAssistantKey().isBlank() ? System.getenv("LIUCHAT_AI_API_KEY") : config.aiAssistantKey();
        int timeout = config.aiAssistantTimeoutSeconds();
        String url = config.aiAssistantUrl();
        String model = config.aiAssistantModel();
        final String selectedSkill = skill;
        final int promptLength = prompt.length();
        client.complete(url, key, model, prompt, question, timeout).whenComplete((answer, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                pending.remove(uuid);
                if (Bukkit.getPlayer(uuid) != player) return;
                if (error != null) {
                    plugin.getLogger().warning("AI 聊天助手请求失败 (skill=" + selectedSkill
                            + ", 提示词字符数=" + promptLength + ", 超时=" + timeout + "秒): " + error);
                    onResult.accept(new Result(Status.FAILED, ""));
                    return;
                }
                String clean = answer.replace('\r', ' ').replace('\n', ' ').replace('§', '&');
                onResult.accept(new Result(Status.OK,
                        clean.substring(0, Math.min(clean.length(), config.aiAssistantMaxAnswer()))));
            });
        });
        return Status.OK;
    }
}

package com.liu.liuchat.service;

import com.liu.liuchat.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.function.Consumer;

/** Optional asynchronous chat moderation; only an explicit ALLOW releases a message. */
public final class AiReviewService {
    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final AiClient client;

    public AiReviewService(JavaPlugin plugin, ConfigManager config, AiClient client) {
        this.plugin = plugin;
        this.config = config;
        this.client = client;
    }

    public boolean enabled() { return config.aiEnabled() && !config.aiUrl().isBlank() && !config.aiModel().isBlank(); }

    public void review(String message, Consumer<Boolean> callback) {
        if (!enabled()) { callback.accept(true); return; }
        boolean failOpen = config.aiFailOpen();
        String key = config.aiKey().isBlank() ? System.getenv("LIUCHAT_AI_API_KEY") : config.aiKey();
        client.complete(config.aiUrl(), key, config.aiModel(), config.aiPrompt(), message,
                config.aiReviewTimeoutSeconds())
                .whenComplete((answer, error) -> {
                    boolean result = decision(answer, error, failOpen);
                    if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> callback.accept(result));
                });
    }

    static boolean decision(String answer, Throwable error, boolean failOpen) {
        if (error != null || answer == null) return failOpen;
        return switch (answer.strip().toUpperCase(Locale.ROOT)) {
            case "ALLOW" -> true;
            case "BLOCK" -> false;
            default -> failOpen;
        };
    }
}

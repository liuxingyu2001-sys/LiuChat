package com.liu.liuchat.service;

import com.liu.liuchat.config.ConfigDefaults;
import com.liu.liuchat.config.ConfigManager;
import com.liu.liuchat.hook.PapiHook;
import com.liu.liuchat.util.Schedulers;
import com.liu.liuchat.util.TextUtil;
import com.liu.liuchat.util.TimePlaceholders;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Each server delivers the shared schedule locally; proxy forwarding would duplicate it. */
public final class ReminderService implements AutoCloseable {
    private final JavaPlugin plugin;
    private final ConfigManager config;
    private List<ReminderPlan> plans = List.of();
    private final Map<String, Instant> delivered = new HashMap<>();
    private Instant lastTick = Instant.now();
    private Schedulers.Handle task;

    public ReminderService(JavaPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
        reload();
    }

    public void reload() {
        List<ReminderPlan> loaded = ReminderPlan.parse(ConfigDefaults.load(plugin, "reminders.yml"));
        plans = loaded;
        delivered.keySet().retainAll(loaded.stream().map(ReminderPlan::id).toList());
    }

    public void start() {
        if (task != null) task.cancel();
        lastTick = Instant.now();
        task = Schedulers.runTimer(plugin, this::tick, 20L, 20L);
    }

    private void tick() {
        Instant now = Instant.now();
        if (!now.isAfter(lastTick)) return; // Do not replay slots when the system clock moves backwards.
        Instant after = lastTick;
        lastTick = now;
        for (ReminderPlan plan : plans) {
            plan.occurrence(after, now, config.server()).ifPresent(occurrence -> {
                Instant previous = delivered.get(plan.id());
                if (previous != null && !occurrence.isAfter(previous)) return;
                delivered.put(plan.id(), occurrence);
                java.time.ZonedDateTime zoned = now.atZone(plan.zone());
                for (String message : plan.messages()) {
                    String text = TimePlaceholders.apply(message.replace("${server}", config.server()), zoned);
                    Schedulers.forEachPlayer(plugin, player -> {
                        if (!plan.permission().isBlank() && !player.hasPermission(plan.permission())) return;
                        try {
                            player.spigot().sendMessage(TextComponent.fromLegacyText(
                                    TextUtil.color(PapiHook.setPlaceholders(player, text))));
                        } catch (RuntimeException e) {
                            plugin.getLogger().warning("提醒 " + plan.id() + " 渲染失败: " + e.getMessage());
                        }
                    });
                    if (plan.console()) plugin.getServer().getConsoleSender().sendMessage(TextUtil.color(text));
                }
            });
        }
    }

    @Override public void close() {
        if (task != null) task.cancel();
        task = null;
    }
}

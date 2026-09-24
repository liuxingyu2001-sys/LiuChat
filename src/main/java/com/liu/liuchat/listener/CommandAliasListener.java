package com.liu.liuchat.listener;

import com.liu.liuchat.config.ConfigManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;
import java.util.Map;

/** Routes conflicting short command labels to our namespaced Bukkit registrations. */
public final class CommandAliasListener implements Listener {
    private static final Map<String, String> ROUTES = Map.of(
            "msg", "msg", "tell", "tell", "w", "msg", "whisper", "msg", "pm", "msg",
            "horn", "horn", "lb", "horn");
    private final ConfigManager config;

    public CommandAliasListener(ConfigManager config) { this.config = config; }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!config.preferOwnCommands()) return;
        String command = event.getMessage();
        int end = command.indexOf(' ');
        String label = command.substring(1, end < 0 ? command.length() : end).toLowerCase(Locale.ROOT);
        String route = ROUTES.get(label);
        if (route != null) event.setMessage("/liuchat:" + route + (end < 0 ? "" : command.substring(end)));
    }
}

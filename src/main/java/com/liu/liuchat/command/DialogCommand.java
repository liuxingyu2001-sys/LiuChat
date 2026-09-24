package com.liu.liuchat.command;

import com.liu.liuchat.config.ConfigDefaults;
import com.liu.liuchat.config.ConfigManager;
import com.liu.liuchat.config.MessageManager;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;

/** The two supported Paper dialog entry points. */
public final class DialogCommand implements ChatCommand {
    private final JavaPlugin plugin;
    private final MessageManager messages;
    private final ConfigManager config;
    private final ColorDialog colors;
    private final AssistantDialog assistantDialog;
    private YamlConfiguration options;

    public DialogCommand(JavaPlugin plugin, MessageManager messages, ConfigManager config,
                         ColorDialog colors, AssistantDialog assistantDialog) {
        this.plugin = plugin;
        this.messages = messages;
        this.config = config;
        this.colors = colors;
        this.assistantDialog = assistantDialog;
        reload();
    }

    public void reload() { options = ConfigDefaults.load(plugin, "dialogs.yml"); }
    @Override public String name() { return "dialog"; }
    @Override public boolean playerOnly() { return true; }

    @Override public void execute(CommandSender sender, String[] args) {
        if (!options.getBoolean("enable", true)) {
            messages.send(sender, "dialog.disabled");
            return;
        }
        if (args.length != 1) {
            messages.send(sender, "dialog.usage");
            return;
        }
        Player player = (Player) sender;
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "ai" -> {
                if (!options.getBoolean("buttons.assistant.enable", true)) { messages.send(player, "dialog.disabled"); return; }
                if (!config.aiAssistantEnabled()) { messages.send(player, "ai.unavailable"); return; }
                assistantDialog.open(player, config.aiAssistantDefaultName(), "聊天助手");
            }
            case "chatcolor" -> {
                if (!options.getBoolean("buttons.chatcolor.enable", true)) { messages.send(player, "dialog.disabled"); return; }
                colors.open(player);
            }
            default -> messages.send(player, "dialog.usage");
        }
    }

    @Override public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return List.of("ai", "chatcolor").stream()
                .filter(value -> value.startsWith(prefix))
                .toList();
    }
}

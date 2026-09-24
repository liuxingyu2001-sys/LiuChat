package com.liu.liuchat.command;

import com.liu.liuchat.config.ConfigManager;
import com.liu.liuchat.config.MessageManager;
import com.liu.liuchat.service.AiAssistantService;
import com.liu.liuchat.service.AiAnswerFormatter;
import com.liu.liuchat.util.TextUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Private in-game assistant, independent of chat moderation. */
public final class AskCommand implements ChatCommand {
    private final ConfigManager config;
    private final MessageManager messages;
    private final AiAssistantService assistant;

    public AskCommand(ConfigManager config, MessageManager messages, AiAssistantService assistant) {
        this.config = config;
        this.messages = messages;
        this.assistant = assistant;
    }

    @Override public String name() { return "ask"; }
    @Override public String permission() { return "liuchat.ask"; }
    @Override public boolean playerOnly() { return true; }

    @Override public void execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
            messages.send(player, "ai.skills", "${skills}", String.join(", ", config.aiAssistantProfiles().keySet()));
            return;
        }
        if (args.length == 0 || String.join(" ", args).isBlank()) {
            messages.send(player, "ai.ask-usage");
            return;
        }
        var profiles = config.aiAssistantProfiles();
        String name = config.aiAssistantDefaultName();
        int start = 0;
        if (args.length > 1 && profiles.containsKey(args[0])) {
            name = args[0];
            start = 1;
        }
        String question = String.join(" ", Arrays.copyOfRange(args, start, args.length)).strip();
        AiAssistantService.Status status = assistant.ask(player, name, question, result -> {
            if (result.status() == AiAssistantService.Status.OK)
                for (String line : AiAnswerFormatter.lines(result.answer()))
                    messages.send(player, "ai.answer", "${answer}", TextUtil.color(line));
            else messages.send(player, "ai.failed");
        });
        switch (status) {
            case OK -> messages.send(player, "ai.thinking");
            case UNAVAILABLE -> messages.send(player, "ai.unavailable");
            case UNKNOWN_ASSISTANT -> messages.send(player, "ai.assistant-unknown", "${assistant}", name);
            case UNKNOWN_SKILL -> messages.send(player, "ai.skill-unknown", "${skill}",
                    profiles.getOrDefault(name, config.aiAssistantDefaultSkill()));
            case TOO_LONG -> messages.send(player, "ai.too-long");
            case BUSY -> messages.send(player, "ai.pending");
            default -> messages.send(player, "ai.failed");
        }
    }

    @Override public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>(config.aiAssistantProfiles().keySet());
        names.add("list");
        return names.stream().filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
}

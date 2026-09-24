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
        final String target = name;
        AiAssistantService.Status status = assistant.ask(player, target, question,
                result -> {
                    if (result.status() == AiAssistantService.Status.OK) sendAnswer(player, target, result.answer());
                    else messages.send(player, "ai.failed");
                });
        switch (status) {
            case OK -> messages.send(player, "ai.thinking", "${name}",
                    TextUtil.color(config.aiAssistantTitle(target)));
            case UNAVAILABLE -> messages.send(player, "ai.unavailable");
            case UNKNOWN_ASSISTANT -> messages.send(player, "ai.assistant-unknown", "${assistant}", name);
            case UNKNOWN_SKILL -> messages.send(player, "ai.skill-unknown", "${skill}",
                    profiles.getOrDefault(name, config.aiAssistantDefaultSkill()));
            case TOO_LONG -> messages.send(player, "ai.too-long");
            case BUSY -> messages.send(player, "ai.pending");
            default -> messages.send(player, "ai.failed");
        }
    }

    /**
     * 多段回答拼成一条消息发出去（段落之间是空行、折行之间是换行），不再逐行刷屏。
     * 前缀按 ai.assistant.answer-prefix 开关，显示名取该助手的自定义名称。
     */
    private void sendAnswer(Player player, String assistant, String answer) {
        String prefix = "";
        int reserved = 0;
        if (config.aiAssistantAnswerPrefix()) {
            prefix = messages.get("ai.answer-prefix", "${name}",
                    TextUtil.color(config.aiAssistantTitle(assistant)));
            reserved = AiAnswerFormatter.visibleWidth(prefix);
        }
        String body = messages.get("ai.answer-text", "${answer}",
                TextUtil.color(AiAnswerFormatter.block(answer, reserved)));
        player.sendMessage(messages.prefix() + prefix + body);
    }

    @Override public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>(config.aiAssistantProfiles().keySet());
        names.add("list");
        return names.stream().filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
}

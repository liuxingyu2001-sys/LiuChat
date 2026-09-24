package com.liu.liuchat.command;

import com.liu.liuchat.config.ConfigManager;
import com.liu.liuchat.config.MessageManager;
import com.liu.liuchat.service.HourlyChatAudit;
import org.bukkit.command.CommandSender;

import java.util.List;

/** Manually review persisted local public chat from the last N hours. */
public final class AuditCommand implements ChatCommand {
    private final ConfigManager config;
    private final MessageManager messages;
    private final HourlyChatAudit audit;

    public AuditCommand(ConfigManager config, MessageManager messages, HourlyChatAudit audit) {
        this.config = config;
        this.messages = messages;
        this.audit = audit;
    }

    @Override public String name() { return "audit"; }
    @Override public String permission() { return "liuchat.audit"; }

    @Override public void execute(CommandSender sender, String[] args) {
        if (!config.aiEnabled() || !config.aiReviewManualEnabled()) {
            messages.send(sender, "ai.audit-disabled");
            return;
        }
        int hours;
        try { hours = args.length == 1 ? Integer.parseInt(args[0]) : -1; }
        catch (NumberFormatException e) { hours = -1; }
        if (hours < 1 || hours > 24) {
            messages.send(sender, "ai.audit-usage");
            return;
        }
        audit.audit(hours, sender);
    }

    @Override public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length != 1) return List.of();
        return List.of("1", "3", "6", "12", "24").stream()
                .filter(value -> value.startsWith(args[0])).toList();
    }
}

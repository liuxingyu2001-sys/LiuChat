package com.liu.liuchat.command;

import com.liu.liuchat.config.MessageManager;
import com.liu.liuchat.service.TellService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Reply to the most recent successfully delivered private message contact. */
public final class ReplyCommand implements ChatCommand {
    private final MessageManager messages;
    private final TellService service;
    private final TellCommand tell;

    public ReplyCommand(MessageManager messages, TellService service, TellCommand tell) {
        this.messages = messages;
        this.service = service;
        this.tell = tell;
    }

    @Override public String name() { return "reply"; }
    @Override public String permission() { return "liuchat.tell"; }
    @Override public boolean playerOnly() { return true; }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0 || String.join(" ", args).isBlank()) {
            messages.send(sender, "reply.usage");
            return;
        }
        Player player = (Player) sender;
        String contact = service.recentContact(player.getUniqueId());
        if (contact == null) {
            messages.send(player, "reply.none");
            return;
        }
        tell.sendTo(player, contact, String.join(" ", args));
    }
}

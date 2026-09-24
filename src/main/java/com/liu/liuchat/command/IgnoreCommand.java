package com.liu.liuchat.command;

import com.liu.liuchat.config.MessageManager;
import com.liu.liuchat.service.IgnoreService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public final class IgnoreCommand implements ChatCommand {
    private final String name;
    private final MessageManager messages;
    private final IgnoreService ignores;

    public IgnoreCommand(String name, MessageManager messages, IgnoreService ignores) {
        this.name = name;
        this.messages = messages;
        this.ignores = ignores;
    }

    @Override public String name() { return name; }
    @Override public boolean playerOnly() { return true; }

    @Override public void execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        if (name.equals("ignorelist")) {
            messages.send(player, "ignore.list", "${players}", String.join(", ", ignores.names(player)));
            return;
        }
        if (args.length != 1 || !args[0].matches("[A-Za-z0-9_]{3,16}")) {
            messages.send(player, "ignore.usage");
            return;
        }
        if (player.getName().equalsIgnoreCase(args[0])) {
            messages.send(player, "ignore.self");
            return;
        }
        boolean changed = name.equals("ignore") ? ignores.add(player, args[0]) : ignores.remove(player, args[0]);
        messages.send(player, changed ? "ignore.success" : "ignore.unchanged", "${player}", args[0]);
    }

    @Override public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length != 1) return List.of();
        return name.equals("unignore") ? ignores.names((Player) sender).stream()
                .filter(n -> n.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))).toList()
                : name.equals("ignore") ? onlinePlayers(args[0]) : List.of();
    }
}

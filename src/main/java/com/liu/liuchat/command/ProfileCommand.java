package com.liu.liuchat.command;

import com.liu.liuchat.config.MessageManager;
import com.liu.liuchat.service.PlayerProfileService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class ProfileCommand implements ChatCommand {
    private final String type;
    private final PlayerProfileService profiles;
    private final MessageManager messages;

    public ProfileCommand(String type, PlayerProfileService profiles, MessageManager messages) {
        this.type = type;
        this.profiles = profiles;
        this.messages = messages;
    }
    @Override public String name() { return type; }
    @Override public String permission() { return type.equals("nick") ? "liuchat.nick" : "liuchat.chatcolor"; }
    @Override public boolean playerOnly() { return true; }
    @Override public void execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        if (args.length != 1) { messages.send(player, "profile.usage"); return; }
        if (type.equals("nick")) {
            if (!args[0].equalsIgnoreCase("off") && !args[0].matches("[\\p{L}\\p{N}_]{2,24}")) {
                messages.send(player, "profile.invalid"); return;
            }
            profiles.nick(player, args[0].equalsIgnoreCase("off") ? "" : args[0]);
        } else {
            if (!args[0].equalsIgnoreCase("off")
                    && !args[0].matches("(?i)&[0-9a-f]|&#[0-9a-f]{6}|<#[0-9a-f]{6}>|<(black|red|green|yellow|blue|aqua|gold|white|gray|dark_[a-z_]+)>") ) {
                messages.send(player, "profile.invalid"); return;
            }
            profiles.color(player, args[0].equalsIgnoreCase("off") ? "" : args[0]);
        }
        messages.send(player, "profile.saved");
    }
}

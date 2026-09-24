package com.liu.liuchat.command;

import com.liu.liuchat.config.MessageManager;
import com.liu.liuchat.service.PlayerProfileService;
import com.liu.liuchat.util.ProfileText;
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
        if (args.length == 0) { messages.send(player, "profile.usage"); return; }
        String input = String.join(" ", args);
        if (type.equals("nick")) {
            boolean formatted = player.hasPermission("liuchat.nick.format");
            String value = ProfileText.normalize(input, formatted);
            profiles.nick(player, value);
        } else {
            boolean formatted = player.hasPermission("liuchat.chatcolor.format");
            String value = ProfileText.normalize(input, formatted);
            profiles.color(player, value);
        }
        messages.send(player, "profile.saved");
    }
}

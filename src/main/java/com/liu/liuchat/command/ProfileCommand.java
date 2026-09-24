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
        String input = String.join(" ", args).trim();
        if (type.equals("nick")) {
            boolean formatted = player.hasPermission("liuchat.nick.format");
            profiles.nick(player, ProfileText.normalize(input, formatted));
        } else if (input.equalsIgnoreCase("off")) {
            profiles.color(player, "");
        } else if (!ProfileText.isColorOnly(input)) {
            // 拒绝正文与未知标签：存进去会成为每条消息的颜色前缀
            messages.send(player, "profile.invalid", "${input}", input.replace('§', '&'));
            return;
        } else {
            // 与 Dialog 同一存储形式（&a / &#RRGGBB / <gradient:...>），
            // ProfileChatColor.apply 与 ColorDialog.fromStored 都认这个格式
            profiles.color(player, input);
        }
        messages.send(player, "profile.saved");
    }
}

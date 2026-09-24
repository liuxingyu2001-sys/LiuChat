package com.liu.liuchat.command;

import com.liu.liuchat.config.MessageManager;
import com.liu.liuchat.service.ChatService;
import com.liu.liuchat.service.CrossServerService;
import com.liu.liuchat.service.MuteService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class HornCommand implements ChatCommand {
    private final MessageManager messages;
    private final ChatService chat;
    private final CrossServerService cross;
    private final MuteService mutes;

    public HornCommand(MessageManager messages, ChatService chat,
                       CrossServerService cross, MuteService mutes) {
        this.messages = messages;
        this.chat = chat;
        this.cross = cross;
        this.mutes = mutes;
    }
    @Override public String name() { return "horn"; }
    @Override public String permission() { return "liuchat.horn"; }
    @Override public boolean playerOnly() { return true; }
    @Override public void execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        if (args.length == 0 || String.join(" ", args).isBlank()) {
            messages.send(player, "horn.usage");
            return;
        }
        var mute = mutes.check(player.getUniqueId().toString(), player.getName());
        if (mute.isPresent()) {
            messages.send(player, "chat.muted", "${time}", messages.muteTimeText(mute.get()),
                    "${reason}", mute.get().reason() == null ? "" : mute.get().reason());
            return;
        }
        String text = String.join(" ", args);
        String message = player.hasPermission("liuchat.color")
                ? com.liu.liuchat.util.ColorParser.playerText(text) : text.replace('§', '&');
        chat.horn(player, message);
        cross.publishHorn(player, message);
    }
}

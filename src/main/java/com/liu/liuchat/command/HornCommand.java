package com.liu.liuchat.command;

import com.liu.liuchat.config.MessageManager;
import com.liu.liuchat.model.MuteData;
import com.liu.liuchat.service.ChatService;
import com.liu.liuchat.service.CrossServerService;
import com.liu.liuchat.service.MuteService;
import com.liu.liuchat.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public final class HornCommand implements ChatCommand {
    private final MessageManager messages;
    private final ChatService chat;
    private final CrossServerService cross;
    private final MuteService mutes;
    private final Database database;

    public HornCommand(MessageManager messages, ChatService chat, CrossServerService cross,
                       MuteService mutes, Database database) {
        this.messages = messages;
        this.chat = chat;
        this.cross = cross;
        this.mutes = mutes;
        this.database = database;
    }

    @Override public String name() { return "horn"; }
    @Override public String permission() { return "liuchat.horn"; }
    @Override public boolean playerOnly() { return false; }

    @Override public void execute(CommandSender sender, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("give")) {
            give(sender, args);
            return;
        }
        if (!(sender instanceof Player player)) {
            messages.send(sender, "player-only");
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("balance")) {
            messages.send(player, "horn.balance", "${count}", String.valueOf(database.hornBalance(player.getUniqueId().toString())));
            return;
        }
        if (args.length == 0 || String.join(" ", args).isBlank()) {
            messages.send(player, "horn.usage");
            return;
        }
        if (!database.spendHorn(player.getUniqueId().toString())) {
            messages.send(player, database.hornBalance(player.getUniqueId().toString()) < 0
                    ? "horn.storage" : "horn.empty");
            return;
        }
        MuteData mute = mutes.check(player.getUniqueId().toString(), player.getName()).orElse(null);
        if (mute != null) {
            database.addHorns(player.getUniqueId().toString(), 1);
            messages.send(player, "chat.muted", "${time}", messages.muteTimeText(mute),
                    "${reason}", mute.reason() == null ? "" : mute.reason());
            return;
        }
        String text = String.join(" ", args);
        String message = player.hasPermission("liuchat.color")
                ? com.liu.liuchat.util.ColorParser.playerText(text) : text.replace('§', '&');
        chat.horn(player, message);
        cross.publishHorn(player, message);
    }

    private void give(CommandSender sender, String[] args) {
        if (!sender.hasPermission("liuchat.horn.give")) {
            messages.send(sender, "no-permission");
            return;
        }
        if (args.length != 3) {
            messages.send(sender, "horn.give-usage");
            return;
        }
        int amount;
        try { amount = Integer.parseInt(args[2]); }
        catch (NumberFormatException e) { amount = 0; }
        if (amount < 1 || amount > 100000) {
            messages.send(sender, "horn.give-invalid");
            return;
        }
        Player online = Bukkit.getPlayerExact(args[1]);
        OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayer(args[1]);
        int balance = database.addHorns(target.getUniqueId().toString(), amount);
        if (balance < 0) {
            messages.send(sender, "horn.storage");
            return;
        }
        messages.send(sender, "horn.give-success", "${player}",
                target.getName() == null ? args[1] : target.getName(), "${count}", String.valueOf(amount));
        if (online != null) messages.send(online, "horn.received", "${count}", String.valueOf(amount));
    }

    @Override public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) return List.of("balance", "give").stream()
                .filter(v -> v.startsWith(args[0].toLowerCase())).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) return onlinePlayers(args[1]);
        return List.of();
    }
}

package com.liu.liuchat.command;

import com.liu.liuchat.config.MessageManager;
import com.liu.liuchat.util.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;

/**
 * /lc tell <玩家> <消息>
 */
public final class TellCommand implements ChatCommand {

    private final MessageManager messages;

    public TellCommand(MessageManager messages) {
        this.messages = messages;
    }

    @Override
    public String name() {
        return "tell";
    }

    @Override
    public String permission() {
        return "liuchat.tell";
    }

    @Override
    public boolean playerOnly() {
        return true;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            messages.send(sender, "tell.usage");
            return;
        }
        Player from = (Player) sender;
        String targetName = args[0];
        if (from.getName().equalsIgnoreCase(targetName)) {
            messages.send(from, "tell.self");
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            messages.send(from, "tell.offline", "${player}", targetName);
            return;
        }
        String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        // 与聊天同一条颜色规则：没 liuchat.color 权限则 & 原样展示
        String message = from.hasPermission("liuchat.color") ? TextUtil.color(text) : text;
        messages.send(from, "tell.to-tell", "${player}", target.getName(), "${message}", message);
        messages.send(target, "tell.from", "${player}", from.getName(), "${message}", message);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return onlinePlayers(args[0]);
        }
        return List.of();
    }
}

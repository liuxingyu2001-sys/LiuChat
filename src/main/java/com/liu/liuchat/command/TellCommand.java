package com.liu.liuchat.command;

import com.liu.liuchat.config.MessageManager;
import com.liu.liuchat.service.TellService;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;

/**
 * /lc tell、/msg、/tell 共用的私聊命令：本服直达，目标不在线则走跨服链路
 * （离线判定与送达提示由 {@link TellService} 的回执机制负责）。
 */
public final class TellCommand implements ChatCommand {

    private final MessageManager messages;
    private final TellService tellService;

    public TellCommand(MessageManager messages, TellService tellService) {
        this.messages = messages;
        this.tellService = tellService;
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
        String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        Player target = Bukkit.getPlayerExact(targetName);
        if (target != null) {
            tellService.deliverLocal(from, target, text);
        } else {
            tellService.sendCross(from, targetName, text);
        }
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return onlinePlayers(args[0]);
        }
        return List.of();
    }
}

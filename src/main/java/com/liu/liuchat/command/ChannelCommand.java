package com.liu.liuchat.command;

import com.liu.liuchat.api.ChannelApi;
import com.liu.liuchat.service.ChannelService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.util.*;

/** /liuc channel [list|join|leave|send|频道] */
public final class ChannelCommand implements ChatCommand {
    private final ChannelService channels;
    public ChannelCommand(ChannelService channels) { this.channels = channels; }
    public String name() { return "channel"; }
    public boolean playerOnly() { return true; }
    public String permission() { return "liuchat.channel"; }
    public void execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            player.sendMessage("§e频道：" + String.join(", ", channels.channels().stream()
                    .filter(c -> c.receivePermission().isBlank() || player.hasPermission(c.receivePermission()))
                    .map(c -> c.id() + "(" + c.displayName() + ")").toList()));
            player.sendMessage("§7当前：" + channels.current(player.getUniqueId()).id()
                    + "；/liuc channel <频道> 切换；join/leave <频道>；send <频道> <消息>");
            return;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (Set.of("join", "leave", "send").contains(action) && args.length < 2) {
            player.sendMessage("§c用法：/liuc channel " + action + " <频道>" + (action.equals("send") ? " <消息>" : "")); return;
        }
        if (action.equals("send")) {
            if (args.length < 3) { player.sendMessage("§c请输入消息。"); return; }
            channels.send(player, args[1], String.join(" ", Arrays.copyOfRange(args, 2, args.length)))
                    .thenAccept(result -> { if (result != ChannelApi.Result.SENT) feedback(player, result); });
            return;
        }
        if (action.equals("leave")) {
            player.sendMessage(channels.unsubscribe(player.getUniqueId(), args[1]) ? "§a已退出频道订阅。" : "§c未订阅此频道。"); return;
        }
        feedback(player, action.equals("join") ? channels.subscribe(player, args[1]) : channels.switchChannel(player, args[0]));
    }
    private void feedback(Player player, ChannelApi.Result result) {
        player.sendMessage(switch (result) {
            case SENT -> "§a频道操作成功。";
            case UNKNOWN_CHANNEL -> "§c频道不存在。";
            case NO_PERMISSION -> "§c没有频道权限。";
            case NOT_MEMBER -> "§c请先使用 /liuc channel join <频道> 订阅。";
            default -> "§c频道操作未完成：" + result;
        });
    }
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length > 2) return List.of();
        List<String> choices = new ArrayList<>();
        if (args.length == 1) choices.addAll(List.of("list", "join", "leave", "send"));
        channels.channels().stream().filter(c -> c.receivePermission().isBlank() || sender.hasPermission(c.receivePermission()))
                .forEach(c -> choices.add(c.id()));
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(c -> c.startsWith(prefix)).sorted().toList();
    }
}

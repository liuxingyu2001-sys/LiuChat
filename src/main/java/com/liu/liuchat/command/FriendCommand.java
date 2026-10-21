package com.liu.liuchat.command;

import com.liu.liuchat.api.Friend;
import com.liu.liuchat.api.FriendApi;
import com.liu.liuchat.api.FriendResult;
import com.liu.liuchat.config.MessageManager;
import com.liu.liuchat.service.FriendResultMessages;
import com.liu.liuchat.service.SocialMenu;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * /friend —— 默认只打开好友界面；另外提供 {@code /friend tp <好友名>} 供习惯打指令的玩家直接传送。
 * 其余操作仍然全部在箱子/对话框内完成。
 */
public final class FriendCommand implements ChatCommand {
    private final SocialMenu menu;
    private final FriendApi friends;
    private final MessageManager messages;

    public FriendCommand(SocialMenu menu, FriendApi friends, MessageManager messages) {
        this.menu = menu;
        this.friends = friends;
        this.messages = messages;
    }

    @Override public String name() { return "friend"; }
    @Override public String permission() { return "liuchat.friend"; }
    @Override public boolean playerOnly() { return true; }

    @Override
    public void execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        if (args.length == 0) {
            menu.openFriendMain(player);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "requests", "incoming" -> menu.openFriendRequests(player, 1);
            case "sent", "outgoing" -> menu.openFriendSent(player, 1);
            case "add" -> menu.openAddFriendDialog(player);
            case "tp", "teleport", "传送" -> teleport(player, args);
            default -> menu.openFriendMain(player);
        }
    }

    /** 直接传送到好友身边：同服落地，跨服交给 HuskHomes，规则与界面左键完全一致。 */
    private void teleport(Player player, String[] args) {
        if (args.length < 2 || args[1].isBlank()) {
            messages.send(player, "command.friend-tp-usage");
            return;
        }
        String name = args[1];
        friends.friends(player.getUniqueId()).thenAccept(list -> {
            Optional<Friend> found = list.stream()
                    .filter(friend -> friend.name().equalsIgnoreCase(name))
                    .findFirst();
            if (found.isEmpty()) {
                messages.send(player, "friend.not-friends", "${player}", name);
                return;
            }
            Friend friend = found.get();
            friends.teleport(player.getUniqueId(), friend.uuid(), friend.name()).thenAccept(result -> {
                String key = switch (result) {
                    case OK -> "friend.teleport-ok";
                    case TELEPORT_CROSS_SERVER -> "friend.teleport-queued";
                    default -> FriendResultMessages.key(result, "friend.teleport-ok");
                };
                messages.send(player, key, "${player}", friend.name());
            });
        });
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("requests", "sent", "add", "tp").stream()
                    .filter(name -> name.startsWith(prefix))
                    .toList();
        }
        if (args.length == 2 && isTeleport(args[0])) {
            return onlinePlayers(args[1]);
        }
        return List.of();
    }

    private static boolean isTeleport(String arg) {
        String lower = arg.toLowerCase(Locale.ROOT);
        return lower.equals("tp") || lower.equals("teleport") || lower.equals("传送");
    }
}

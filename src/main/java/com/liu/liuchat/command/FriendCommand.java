package com.liu.liuchat.command;

import com.liu.liuchat.service.SocialMenuService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/** /liuc friend —— 只打开好友界面，所有操作在箱子/对话框内完成。 */
public final class FriendCommand implements ChatCommand {
    private final SocialMenuService menu;

    public FriendCommand(SocialMenuService menu) {
        this.menu = menu;
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
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "requests", "incoming" -> menu.openFriendRequests(player, 1);
            case "sent", "outgoing" -> menu.openFriendSent(player, 1);
            case "add" -> menu.openAddFriendDialog(player);
            default -> menu.openFriendMain(player);
        }
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}

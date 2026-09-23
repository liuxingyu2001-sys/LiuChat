package com.liu.liuchat.command;

import com.liu.liuchat.LiuChat;
import com.liu.liuchat.config.MessageManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.logging.Level;

/**
 * 子命令与顶层直注册命令共用的分发逻辑：权限 / 玩家限定 / 异常兜底 / tab。
 */
final class CommandSupport {

    private CommandSupport() {
    }

    static boolean dispatch(CommandSender sender, ChatCommand command, String[] args,
                            String label, MessageManager messages) {
        if (!hasAccess(sender, command)) {
            if (command.playerOnly() && !(sender instanceof Player)) {
                messages.send(sender, "player-only");
            } else {
                messages.send(sender, "no-permission");
            }
            return true;
        }
        try {
            command.execute(sender, args);
        } catch (Exception e) {
            LiuChat.instance().getLogger()
                    .log(Level.SEVERE, "执行 /" + label + " 出错", e);
            messages.send(sender, "command.error");
        }
        return true;
    }

    static List<String> complete(CommandSender sender, ChatCommand command, String[] args) {
        if (!hasAccess(sender, command)) {
            return List.of();
        }
        return command.tabComplete(sender, args);
    }

    static boolean hasAccess(CommandSender sender, ChatCommand command) {
        String permission = command.permission();
        return (permission == null || sender.hasPermission(permission))
                && (!command.playerOnly() || sender instanceof Player);
    }
}

package com.liu.liuchat.command;

import com.liu.liuchat.config.MessageManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;

/**
 * 顶层直注册命令的桥：把 plugin.yml 里独立声明的命令（/msg /tell 等）
 * 包装到 {@link ChatCommand}，复用与 /liuc 子命令完全相同的
 * 权限检查 / 玩家限定 / 异常兜底 / tab 补全。
 */
public final class DirectCommandBridge implements CommandExecutor, TabCompleter {

    private final ChatCommand command;
    private final MessageManager messages;

    public DirectCommandBridge(ChatCommand command, MessageManager messages) {
        this.command = command;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        return CommandSupport.dispatch(sender, command, args, label, messages);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        return CommandSupport.complete(sender, command, args);
    }
}

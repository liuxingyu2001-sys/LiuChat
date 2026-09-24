package com.liu.liuchat.command;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * /liuc 子命令：一个功能一个类，{@link CommandRouter} 统一注册与分发。
 */
public interface ChatCommand {

    String name();

    /** null = 无需权限 */
    default String permission() {
        return null;
    }

    /** true = 仅玩家可用 */
    default boolean playerOnly() {
        return false;
    }

    /**
     * 执行命令，args 已去掉子命令名本身。
     */
    void execute(CommandSender sender, String[] args);

    /**
     * tab 补全，args 同样已去掉子命令名本身。
     */
    default List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }

    /** 按前缀过滤在线玩家名，子命令 tab 补全共用 */
    default List<String> onlinePlayers(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(lower))
                .sorted()
                .toList();
    }
}

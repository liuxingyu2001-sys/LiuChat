package com.liu.liuchat.command;

import com.liu.liuchat.config.ConfigManager;
import com.liu.liuchat.config.MessageManager;
import org.bukkit.command.CommandSender;

/**
 * /lc reload —— 重载配置与语言文件。
 */
public final class ReloadCommand implements ChatCommand {

    private final ConfigManager config;
    private final MessageManager messages;

    public ReloadCommand(ConfigManager config, MessageManager messages) {
        this.config = config;
        this.messages = messages;
    }

    @Override
    public String name() {
        return "reload";
    }

    @Override
    public String permission() {
        return "liuchat.reload";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        config.reload();
        messages.reload();
        messages.send(sender, "reload.success");
    }
}

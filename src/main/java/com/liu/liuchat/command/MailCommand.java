package com.liu.liuchat.command;

import com.liu.liuchat.service.SocialMenuService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/** /liuc mail —— 只打开邮箱界面，阅读/领取/写邮件都在箱子或对话框内完成。 */
public final class MailCommand implements ChatCommand {
    private final SocialMenuService menu;

    public MailCommand(SocialMenuService menu) {
        this.menu = menu;
    }

    @Override public String name() { return "mail"; }
    @Override public String permission() { return "liuchat.mail"; }
    @Override public boolean playerOnly() { return true; }

    @Override
    public void execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        if (args.length > 0 && args[0].equalsIgnoreCase("compose")) {
            menu.openCompose(player);
        } else {
            menu.openMailList(player);
        }
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}

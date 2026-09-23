package com.liu.liuchat.command;

import com.liu.liuchat.config.MessageManager;
import com.liu.liuchat.LiuChat;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * /lc 主命令路由器：按第一个参数分发到各 {@link ChatCommand}，
 * 统一做玩家限定/权限检查/异常兜底，tab 补全同样分流。
 */
public final class CommandRouter implements CommandExecutor, TabCompleter {

    private final MessageManager messages;
    private final Map<String, ChatCommand> commands = new LinkedHashMap<>();

    public CommandRouter(MessageManager messages) {
        this.messages = messages;
    }

    public void register(ChatCommand command) {
        commands.put(command.name().toLowerCase(Locale.ROOT), command);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        ChatCommand sub = commands.get(args[0].toLowerCase(Locale.ROOT));
        if (sub == null) {
            messages.send(sender, "command.unknown");
            sendHelp(sender);
            return true;
        }
        if (sub.playerOnly() && !(sender instanceof Player)) {
            messages.send(sender, "player-only");
            return true;
        }
        String permission = sub.permission();
        if (permission != null && !sender.hasPermission(permission)) {
            messages.send(sender, "no-permission");
            return true;
        }
        try {
            sub.execute(sender, Arrays.copyOfRange(args, 1, args.length));
        } catch (Exception e) {
            LiuChat.instance().getLogger()
                    .log(Level.SEVERE, "执行 /" + label + " " + args[0] + " 出错", e);
            messages.send(sender, "command.error");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return commands.values().stream()
                    .filter(sub -> hasAccess(sender, sub))
                    .map(ChatCommand::name)
                    .filter(name -> name.startsWith(prefix))
                    .sorted()
                    .toList();
        }
        ChatCommand sub = commands.get(args[0].toLowerCase(Locale.ROOT));
        if (sub == null || !hasAccess(sender, sub)) {
            return List.of();
        }
        return sub.tabComplete(sender, Arrays.copyOfRange(args, 1, args.length));
    }

    private boolean hasAccess(CommandSender sender, ChatCommand sub) {
        String permission = sub.permission();
        return (permission == null || sender.hasPermission(permission))
                && (!sub.playerOnly() || sender instanceof Player);
    }

    private void sendHelp(CommandSender sender) {
        messages.send(sender, "help.header");
        for (ChatCommand sub : commands.values()) {
            if (!hasAccess(sender, sub)) {
                continue;
            }
            messages.send(sender, "help.line",
                    "${sub}", sub.name(),
                    "${desc}", messages.getRaw("help." + sub.name() + ".desc"));
        }
    }
}

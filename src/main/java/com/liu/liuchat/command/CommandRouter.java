package com.liu.liuchat.command;

import com.liu.liuchat.LiuChat;
import com.liu.liuchat.config.MessageManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /lc 主命令路由器：按第一个参数分发到各 {@link ChatCommand}，
 * 权限/玩家限定/异常兜底交给 {@link CommandSupport}，tab 补全同样分流。
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
        return CommandSupport.dispatch(sender, sub, Arrays.copyOfRange(args, 1, args.length),
                label + " " + args[0], messages);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return commands.values().stream()
                    .filter(sub -> CommandSupport.hasAccess(sender, sub))
                    .map(ChatCommand::name)
                    .filter(name -> name.startsWith(prefix))
                    .sorted()
                    .toList();
        }
        ChatCommand sub = commands.get(args[0].toLowerCase(Locale.ROOT));
        if (sub == null) {
            return List.of();
        }
        return CommandSupport.complete(sender, sub, Arrays.copyOfRange(args, 1, args.length));
    }

    private void sendHelp(CommandSender sender) {
        messages.send(sender, "help.header");
        for (ChatCommand sub : commands.values()) {
            if (!CommandSupport.hasAccess(sender, sub)) {
                continue;
            }
            messages.send(sender, "help.line",
                    "${sub}", sub.name(),
                    "${desc}", messages.get("help." + sub.name() + ".desc"));
        }
    }
}

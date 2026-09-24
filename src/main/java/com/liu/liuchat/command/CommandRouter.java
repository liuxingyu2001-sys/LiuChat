package com.liu.liuchat.command;

import com.liu.liuchat.LiuChat;
import com.liu.liuchat.config.MessageManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /liuc 主命令路由器：按第一个参数分发到各 {@link ChatCommand}，
 * 权限/玩家限定/异常兜底交给 {@link CommandSupport}，tab 补全同样分流。
 */
public final class CommandRouter implements CommandExecutor, TabCompleter {

    private final MessageManager messages;
    private final Map<String, ChatCommand> commands = new LinkedHashMap<>();
    /** 子命令名 -> 已直挂成顶层的命令名（/mute、/msg 等），帮助里按顶层命令展示 */
    private final Map<String, String> direct = new HashMap<>();

    public CommandRouter(MessageManager messages) {
        this.messages = messages;
    }

    public void register(ChatCommand command) {
        commands.put(command.name().toLowerCase(Locale.ROOT), command);
    }

    /** 同一个子命令被绑成顶层命令时登记，帮助行用 /顶层名 而不是 /liuc 子命令 */
    public void markDirect(String sub, String topLevel) {
        direct.put(sub.toLowerCase(Locale.ROOT), topLevel.toLowerCase(Locale.ROOT));
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
            String name = sub.name();
            String top = direct.get(name.toLowerCase(Locale.ROOT));
            messages.send(sender, "help.cmd",
                    "${command}", label(name, top),
                    "${desc}", fixDesc(name, top, messages.get("help." + name + ".desc")));
        }
    }

    /** 帮助行的命令：绑过顶层的用 /mute，没绑的用 /liuc ask */
    static String label(String sub, String top) {
        return top == null ? "/liuc " + sub : "/" + top;
    }

    /**
     * desc 里若重复写了命令名（老语言文件的用法是 {@code &e/liuc ask <问题>} 这种写法），
     * 把它去掉，帮助行已经有命令了，别连着出现两遍。
     */
    static String fixDesc(String sub, String top, String desc) {
        String out = desc.replace("/liuc " + sub + " ", "");
        return top == null ? out : out.replace("/" + top + " ", "");
    }
}

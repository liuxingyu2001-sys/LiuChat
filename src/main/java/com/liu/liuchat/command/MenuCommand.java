package com.liu.liuchat.command;

import com.liu.liuchat.config.MessageManager;
import com.liu.liuchat.hook.ChatWorkspace;
import com.liu.liuchat.hook.DialogMenuBridge;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;

/**
 * /liuc menu [页面]：打开聊天菜单界面。
 * <p>
 * 优先级：DialogMenu 内置菜单 → ArcMenu 聊天工作区 → 提示不可用。
 * 两者都是软依赖，没有安装时给出提示，不报错；菜单文件缺失时先补一份再打开。
 * DialogMenu 那条路只是以玩家身份执行 {@code dmenu open liuchat [页面]}，
 * 所以业务权限、菜单校验、资源包要求都由 DialogMenu 与 LiuChat 各自负责。
 */
public final class MenuCommand implements ChatCommand {
    private final JavaPlugin plugin;
    private final MessageManager messages;
    /** 插件启用时才确定（取决于装了哪个界面插件），通过 setter 注入。 */
    private ChatWorkspace workspace;

    public MenuCommand(JavaPlugin plugin, MessageManager messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    public void setWorkspace(ChatWorkspace workspace) {
        this.workspace = workspace;
    }

    @Override public String name() { return "menu"; }
    @Override public boolean playerOnly() { return true; }

    @Override
    public void execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        if (DialogMenuBridge.available()) {
            DialogMenuBridge.install(plugin); // 菜单被删或 DialogMenu 后装：这里补齐
            String page = args.length == 0 ? "" : args[0].trim();
            String command = "dmenu open " + DialogMenuBridge.MENU_ID
                    + (page.isEmpty() ? "" : " " + page);
            if (!player.performCommand(command)) {
                // DialogMenu 没注册 /dmenu，或菜单校验没通过
                messages.send(sender, "menu.failed");
            }
            return;
        }
        if (workspace != null) {
            List<String> arguments = args.length == 0 ? List.of() : List.of(args[0]);
            if (workspace.open(player, arguments)) return;
            messages.send(sender, "menu.failed");
            return;
        }
        messages.send(sender, "menu.unavailable");
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length != 1) return List.of();
        if (!DialogMenuBridge.available()) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return DialogMenuBridge.pages(plugin).stream()
                .filter(page -> page.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }
}

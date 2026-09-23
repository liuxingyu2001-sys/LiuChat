package com.liu.liuchat;

import com.liu.liuchat.command.CommandRouter;
import com.liu.liuchat.command.MuteCommand;
import com.liu.liuchat.command.ReloadCommand;
import com.liu.liuchat.command.TellCommand;
import com.liu.liuchat.command.UnmuteCommand;
import com.liu.liuchat.config.ConfigManager;
import com.liu.liuchat.config.MessageManager;
import com.liu.liuchat.hook.PapiHook;
import com.liu.liuchat.listener.ChatListener;
import com.liu.liuchat.service.ChatService;
import com.liu.liuchat.service.MuteService;
import com.liu.liuchat.storage.Database;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * LiuChat 主类。
 * <p>
 * 刻意不依赖任何第三方框架（如 HandyLib）：命令路由、配置、语言、SQLite、
 * PAPI 挂钩全部在本插件内自建，代码完全可控。
 *
 * @author liuxingyu2001
 */
public final class LiuChat extends JavaPlugin {

    private static LiuChat instance;

    private ConfigManager configManager;
    private MessageManager messageManager;
    private Database database;
    private MuteService muteService;
    private ChatService chatService;

    public static LiuChat instance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;

        // 1. 配置与语言文件
        configManager = new ConfigManager(this);
        configManager.load();
        messageManager = new MessageManager(this);
        messageManager.load();

        // 2. 存储（失败自动降级为仅内存，不阻塞启用）
        database = new Database(this);
        boolean dbReady = database.init();
        muteService = new MuteService(database);
        muteService.loadAll();

        chatService = new ChatService(configManager);

        // 3. 命令：/lc <子命令>
        PluginCommand mainCommand = getCommand("liuchat");
        if (mainCommand == null) {
            getLogger().severe("plugin.yml 缺少 liuchat 命令定义，插件停用");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        CommandRouter router = new CommandRouter(messageManager);
        router.register(new ReloadCommand(configManager, messageManager));
        router.register(new MuteCommand(messageManager, muteService));
        router.register(new UnmuteCommand(messageManager, muteService));
        router.register(new TellCommand(messageManager));
        mainCommand.setExecutor(router);
        mainCommand.setTabCompleter(router);

        // 4. 事件监听
        getServer().getPluginManager().registerEvents(
                new ChatListener(configManager, messageManager, muteService, chatService), this);

        // 5. PlaceholderAPI 挂钩（没装则自动跳过）
        PapiHook.init(this, configManager, muteService);

        getLogger().info("LiuChat 已启用" + (dbReady
                ? "（SQLite 就绪）"
                : "（数据库不可用，禁言仅保存在内存）"));
    }

    @Override
    public void onDisable() {
        if (database != null) {
            database.close();
        }
        instance = null;
    }
}

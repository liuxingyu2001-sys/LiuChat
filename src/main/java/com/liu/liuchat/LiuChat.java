package com.liu.liuchat;

import com.liu.liuchat.command.ChatCommand;
import com.liu.liuchat.command.CommandRouter;
import com.liu.liuchat.command.DirectCommandBridge;
import com.liu.liuchat.command.MuteCommand;
import com.liu.liuchat.command.ReloadCommand;
import com.liu.liuchat.command.TellCommand;
import com.liu.liuchat.command.UnmuteCommand;
import com.liu.liuchat.config.ConfigManager;
import com.liu.liuchat.config.MessageManager;
import com.liu.liuchat.hook.PapiHook;
import com.liu.liuchat.listener.ChatListener;
import com.liu.liuchat.service.ChatService;
import com.liu.liuchat.service.CrossServerService;
import com.liu.liuchat.service.MuteService;
import com.liu.liuchat.storage.Database;
import com.liu.liuchat.storage.DatabaseFactory;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * LiuChat 主类。
 * <p>
 * 刻意不依赖任何第三方框架（如 HandyLib）：命令路由、配置、语言、
 * SQLite/MySQL、跨服、PAPI 挂钩全部在本插件内自建，代码完全可控。
 *
 * @author liuxingyu2001
 */
public final class LiuChat extends JavaPlugin {

    private static LiuChat instance;

    private ConfigManager configManager;
    private MessageManager messageManager;
    private Database database;
    private MuteService muteService;
    private CrossServerService crossServer;

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

        // 2. 存储（sqlite/mysql 按配置选择；失败自动降级为仅内存，不阻塞启用）
        database = DatabaseFactory.create(this, configManager);
        muteService = new MuteService(database);
        muteService.loadAll();

        ChatService chatService = new ChatService(configManager);

        // 3. 跨服（BungeeCord plugin messaging；单服/无代理环境静默无副作用）
        crossServer = new CrossServerService(this, configManager, chatService);

        // 4. 命令：/lc 子命令 + 顶层直注册的 /msg /tell
        CommandRouter router = new CommandRouter(messageManager);
        router.register(new ReloadCommand(configManager, messageManager));
        router.register(new MuteCommand(messageManager, muteService));
        router.register(new UnmuteCommand(messageManager, muteService));
        ChatCommand tell = new TellCommand(messageManager);
        router.register(tell);

        PluginCommand mainCommand = requireCommand("liuchat");
        if (mainCommand == null) {
            return;
        }
        mainCommand.setExecutor(router);
        mainCommand.setTabCompleter(router);
        bindDirect("msg", tell);
        bindDirect("tell", tell);

        // 5. 事件监听（跨服服务是 PluginMessageListener，注册发生在其构造器里）
        getServer().getPluginManager().registerEvents(
                new ChatListener(configManager, messageManager, muteService, chatService, crossServer), this);

        // 6. 定时与数据库对账（跨服共享禁言的同步入口）
        int syncInterval = configManager.syncInterval();
        if (syncInterval > 0 && database.isReady()) {
            long ticks = syncInterval * 20L;
            getServer().getScheduler().runTaskTimerAsynchronously(
                    this, muteService::loadAll, ticks, ticks);
        }

        // 7. PlaceholderAPI 挂钩（没装则自动跳过）
        PapiHook.init(this, configManager, muteService);

        getLogger().info("LiuChat 已启用（存储: " + configManager.storageType()
                + (database.isReady() ? " 就绪" : " 不可用-仅内存")
                + "，跨服: " + (configManager.crossServerEnabled() ? "开" : "关") + "）");
    }

    /** 取 plugin.yml 命令，缺失视为安装损坏 */
    private PluginCommand requireCommand(String name) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().severe("plugin.yml 缺少 " + name + " 命令定义，插件停用");
            getServer().getPluginManager().disablePlugin(this);
        }
        return command;
    }

    /** 把一个 ChatCommand 绑成独立顶层命令（含 tab 补全） */
    private void bindDirect(String name, ChatCommand command) {
        PluginCommand pluginCommand = requireCommand(name);
        if (pluginCommand == null) {
            return;
        }
        DirectCommandBridge bridge = new DirectCommandBridge(command, messageManager);
        pluginCommand.setExecutor(bridge);
        pluginCommand.setTabCompleter(bridge);
    }

    @Override
    public void onDisable() {
        if (crossServer != null) {
            crossServer.close();
        }
        if (database != null) {
            database.close();
        }
        instance = null;
    }
}

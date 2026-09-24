package com.liu.liuchat;

import com.liu.liuchat.command.ChatCommand;
import com.liu.liuchat.command.AskCommand;
import com.liu.liuchat.command.AuditCommand;
import com.liu.liuchat.command.CommandRouter;
import com.liu.liuchat.command.DirectCommandBridge;
import com.liu.liuchat.command.DialogCommand;
import com.liu.liuchat.command.ColorDialog;
import com.liu.liuchat.command.AssistantDialog;
import com.liu.liuchat.command.HornCommand;
import com.liu.liuchat.command.IgnoreCommand;
import com.liu.liuchat.command.MuteCommand;
import com.liu.liuchat.command.ProfileCommand;
import com.liu.liuchat.command.ReloadCommand;
import com.liu.liuchat.command.TellCommand;
import com.liu.liuchat.command.UnmuteCommand;
import com.liu.liuchat.config.ConfigManager;
import com.liu.liuchat.config.MessageManager;
import com.liu.liuchat.hook.PapiHook;
import com.liu.liuchat.listener.ChatListener;
import com.liu.liuchat.listener.CommandAliasListener;
import com.liu.liuchat.listener.NpcAssistantBridge;
import com.liu.liuchat.service.HourlyChatAudit;
import com.liu.liuchat.service.AiClient;
import com.liu.liuchat.service.AiAssistantService;
import com.liu.liuchat.service.AiSkillService;
import com.liu.liuchat.service.AiSessionStore;
import com.liu.liuchat.service.ChatLogService;
import com.liu.liuchat.service.ChatPresentation;
import com.liu.liuchat.service.ChatService;
import com.liu.liuchat.service.CrossServerService;
import com.liu.liuchat.service.IgnoreService;
import com.liu.liuchat.service.ItemShowcase;
import com.liu.liuchat.service.MuteService;
import com.liu.liuchat.service.PlayerProfileService;
import com.liu.liuchat.service.TellService;
import com.liu.liuchat.storage.Database;
import com.liu.liuchat.storage.DatabaseFactory;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
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
    private ChatLogService chatLogs;
    private AiSessionStore aiSessions;

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

        ItemShowcase items = new ItemShowcase();
        ChatPresentation presentation = new ChatPresentation(this, configManager, items);
        ChatService chatService = new ChatService(this, configManager, presentation, items);
        chatLogs = new ChatLogService(this, configManager);
        chatService.setChatLogService(chatLogs);

        // 3. 跨服（BungeeCord plugin messaging；单服/无代理环境静默无副作用）
        crossServer = new CrossServerService(this, configManager, chatService);
        TellService tellService = new TellService(this, messageManager, crossServer);
        crossServer.setTellService(tellService);
        crossServer.setMuteService(muteService);
        IgnoreService ignores = new IgnoreService(database);
        PlayerProfileService profiles = new PlayerProfileService(database);
        chatService.setPlayerProfileService(profiles);
        tellService.setPresentation(presentation, profiles);
        chatService.setIgnoreService(ignores);
        tellService.setIgnoreService(ignores);
        tellService.setConfig(configManager);
        tellService.setChatLogService(chatLogs);

        // 4. 命令：/lc 子命令 + 顶层直注册的 /msg /tell /mute /unmute
        CommandRouter router = new CommandRouter(messageManager);
        AiClient aiClient = new AiClient();
        HourlyChatAudit audit = new HourlyChatAudit(this, configManager, messageManager, chatLogs, aiClient);
        audit.start();
        router.register(new AuditCommand(configManager, messageManager, audit));
        AiSkillService skills = new AiSkillService(getDataFolder().toPath().resolve("skills"));
        try {
            skills.reload();
        } catch (java.io.IOException e) {
            getLogger().warning("AI skills 加载失败: " + e.getMessage());
        }
        aiSessions = new AiSessionStore(getDataFolder().toPath().resolve("ai-sessions.json"), getLogger());
        aiSessions.setPersist(configManager.aiAssistantHistoryPersist());
        aiSessions.load();
        aiSessions.start(this);
        AiAssistantService assistantService =
                new AiAssistantService(this, configManager, aiClient, skills, aiSessions);
        router.register(new AskCommand(configManager, messageManager, assistantService));
        ColorDialog colorDialog = new ColorDialog(this, profiles, messageManager);
        AssistantDialog assistantDialog = new AssistantDialog(this, configManager, messageManager, assistantService);
        DialogCommand dialog = new DialogCommand(this, messageManager, configManager, colorDialog, assistantDialog);
        NpcAssistantBridge npcBridge = new NpcAssistantBridge(this, assistantDialog);
        router.register(new ReloadCommand(configManager, messageManager, presentation, dialog, skills, npcBridge,
                aiSessions));
        router.register(new ChatCommand() {
            @Override public String name() { return "item"; }
            @Override public boolean playerOnly() { return true; }
            @Override public void execute(CommandSender sender, String[] args) {
                if (args.length == 1) items.open((Player) sender, args[0]);
                else messageManager.send(sender, "item.usage");
            }
        });
        ChatCommand mute = new MuteCommand(messageManager, muteService, crossServer);
        ChatCommand unmute = new UnmuteCommand(messageManager, muteService, crossServer);
        router.register(mute);
        router.register(unmute);
        ChatCommand horn = new HornCommand(messageManager, chatService, crossServer, muteService, database);
        router.register(horn);
        router.register(new IgnoreCommand("ignore", messageManager, ignores));
        router.register(new IgnoreCommand("unignore", messageManager, ignores));
        router.register(new IgnoreCommand("ignorelist", messageManager, ignores));
        ChatCommand nick = new ProfileCommand("nick", profiles, messageManager);
        ChatCommand chatColor = new ProfileCommand("chatcolor", profiles, messageManager);
        router.register(nick);
        router.register(chatColor);
        router.register(dialog);
        ChatCommand tell = new TellCommand(messageManager, tellService, configManager, crossServer);
        router.register(tell);

        PluginCommand mainCommand = requireCommand("liuchat");
        if (mainCommand == null) {
            return;
        }
        mainCommand.setExecutor(router);
        mainCommand.setTabCompleter(router);
        if (!bindDirect("msg", tell, router)) return;
        if (!bindDirect("tell", tell, router)) return;
        if (!bindDirect("horn", horn, router)) return;
        if (!bindDirect("nick", nick, router)) return;
        if (!bindDirect("chatcolor", chatColor, router)) return;
        if (!bindDirect("mute", mute, router)) return;
        if (!bindDirect("unmute", unmute, router)) return;

        // 5. 事件监听（跨服服务是 PluginMessageListener，注册发生在其构造器里）
        getServer().getPluginManager().registerEvents(items, this);
        getServer().getPluginManager().registerEvents(ignores, this);
        getServer().getPluginManager().registerEvents(profiles, this);
        getServer().getPluginManager().registerEvents(new CommandAliasListener(configManager), this);
        getServer().getPluginManager().registerEvents(
                new ChatListener(this, configManager, messageManager, muteService, chatService, crossServer,
                        audit), this);

        // 6. 定时与数据库对账（跨服共享禁言的同步入口）
        int syncInterval = configManager.syncInterval();
        if (syncInterval > 0 && database.isReady()) {
            long ticks = syncInterval * 20L;
            getServer().getScheduler().runTaskTimerAsynchronously(
                    this, muteService::loadAll, ticks, ticks);
        }

        // 7. PlaceholderAPI 挂钩（没装则自动跳过）
        PapiHook.init(this, configManager, muteService, profiles);

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

    /** 把一个 ChatCommand 绑成独立顶层命令（含 tab 补全），并登记到帮助里按顶层名展示 */
    private boolean bindDirect(String name, ChatCommand command, CommandRouter router) {
        PluginCommand pluginCommand = requireCommand(name);
        if (pluginCommand == null) {
            return false;
        }
        DirectCommandBridge bridge = new DirectCommandBridge(command, messageManager);
        pluginCommand.setExecutor(bridge);
        pluginCommand.setTabCompleter(bridge);
        // 同一子命令绑了多个顶层名（msg/tell）时后者胜出，帮助里展示更通用的那个
        router.markDirect(command.name(), name);
        return true;
    }

    @Override
    public void onDisable() {
        if (crossServer != null) {
            crossServer.close();
        }
        if (chatLogs != null) chatLogs.close();
        if (aiSessions != null) {
            aiSessions.close();
        }
        if (database != null) {
            database.close();
        }
        instance = null;
    }
}

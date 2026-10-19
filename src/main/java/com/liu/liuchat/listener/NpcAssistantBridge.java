package com.liu.liuchat.listener;

import com.liu.liuchat.command.AssistantDialog;
import com.liu.liuchat.config.ConfigDefaults;
import com.liu.liuchat.config.ConfigManager;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/** Optional Citizens right-click bridge; no Citizens classes are loaded when absent. */
public final class NpcAssistantBridge implements Listener {
    /** 包级可见以便单测；sharedMemory = 该 NPC 记忆开关解析后的最终值。 */
    record Binding(String assistant, String title, boolean sharedMemory) { }
    private final JavaPlugin plugin;
    private final AssistantDialog dialog;
    private final ConfigManager config;
    private volatile Map<Integer, Binding> bindings = Map.of();
    private final Map<java.util.UUID, Long> lastClick = new HashMap<>();
    private volatile double maxDistance = 6;
    private volatile boolean cancelOtherActions = true;

    public NpcAssistantBridge(JavaPlugin plugin, AssistantDialog dialog, ConfigManager config) {
        this.plugin = plugin;
        this.dialog = dialog;
        this.config = config;
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        var citizens = Bukkit.getPluginManager().getPlugin("Citizens");
        if (citizens == null || !citizens.isEnabled()) return;
        try {
            Class<?> type = Class.forName("net.citizensnpcs.api.event.NPCRightClickEvent", false,
                    citizens.getClass().getClassLoader());
            @SuppressWarnings("unchecked") Class<? extends Event> eventType = (Class<? extends Event>) type;
            Method getNpc = type.getMethod("getNPC");
            Class<?> npcType = Class.forName("net.citizensnpcs.api.npc.NPC", false,
                    citizens.getClass().getClassLoader());
            Method getId = npcType.getMethod("getId");
            Method getEntity = npcType.getMethod("getEntity");
            Method getClicker = type.getMethod("getClicker");
            Bukkit.getPluginManager().registerEvent(eventType, this, EventPriority.NORMAL, (listener, event) -> {
                try {
                    Player player = (Player) getClicker.invoke(event);
                    Object npc = getNpc.invoke(event);
                    int id = (int) getId.invoke(npc);
                    Binding binding = bindings.get(id);
                    if (binding == null) return;
                    Entity entity = (Entity) getEntity.invoke(npc);
                    if (entity == null || !player.getWorld().equals(entity.getWorld())
                            || player.getLocation().distanceSquared(entity.getLocation()) > maxDistance * maxDistance)
                        return;
                    long now = System.currentTimeMillis();
                    if (now - lastClick.getOrDefault(player.getUniqueId(), 0L) < 2000L) return;
                    lastClick.put(player.getUniqueId(), now);
                    if (cancelOtherActions && event instanceof org.bukkit.event.Cancellable cancellable)
                        cancellable.setCancelled(true);
                    dialog.open(player, binding.assistant(), binding.title(), binding.sharedMemory());
                } catch (ReflectiveOperationException ex) {
                    plugin.getLogger().warning("Citizens NPC 助手事件处理失败: " + ex);
                }
            }, plugin, true);
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("Citizens API 不兼容，NPC 助手未启用: " + ex);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastClick.remove(event.getPlayer().getUniqueId());
    }

    public void reload() {
        migrateSharedConfig();
        var npcConfig = ConfigDefaults.load(plugin, "npc-assistants.yml");
        maxDistance = Math.max(1, Math.min(16, npcConfig.getDouble("max-distance", 6)));
        cancelOtherActions = npcConfig.getBoolean("cancel-other-actions", true);
        // 记忆开关三级覆盖：npcs.<id> → 本文件顶层 → ai.yml 的 ai.assistant.shared-memory
        boolean defaultShared = npcConfig.contains("shared-memory")
                ? npcConfig.getBoolean("shared-memory", true)
                : config.aiAssistantSharedMemory();
        bindings = parseBindings(npcConfig, defaultShared);
    }

    /**
     * npc-assistants.yml 曾放在共享目录；现改为每服本地。
     * 首次升级且本地文件不存在时，把共享目录里的自定义绑定复制到本地一次，之后只读本地。
     */
    private void migrateSharedConfig() {
        if (!(plugin instanceof com.liu.liuchat.LiuChat chat)) return;
        File local = new File(plugin.getDataFolder(), "npc-assistants.yml");
        File shared = new File(chat.getConfigRoot(), "npc-assistants.yml");
        if (local.exists() || !shared.isFile() || shared.getAbsoluteFile().equals(local.getAbsoluteFile())) return;
        try {
            Files.createDirectories(local.getParentFile().toPath());
            Files.copy(shared.toPath(), local.toPath());
            plugin.getLogger().info("已把共享 npc-assistants.yml 迁移为每服本地配置: " + local.getAbsolutePath());
        } catch (java.io.IOException error) {
            plugin.getLogger().warning("迁移共享 npc-assistants.yml 失败，将使用内置默认值: " + error.getMessage());
        }
    }

    static Map<Integer, Binding> parseBindings(YamlConfiguration config, boolean defaultSharedMemory) {
        Map<Integer, Binding> loaded = new HashMap<>();
        var nodes = config.getConfigurationSection("npcs");
        if (nodes != null) for (String key : nodes.getKeys(false)) {
            try {
                int id = Integer.parseInt(key);
                if (id < 0 || !nodes.getBoolean(key + ".enable", true)) continue;
                String assistant = nodes.getString(key + ".assistant", "");
                if (!assistant.matches("[a-zA-Z0-9_-]{1,48}")) continue;
                boolean shared = nodes.getBoolean(key + ".shared-memory", defaultSharedMemory);
                loaded.put(id, new Binding(assistant, nodes.getString(key + ".title", "聊天助手"), shared));
            } catch (NumberFormatException ignored) { }
        }
        return Map.copyOf(loaded);
    }
}

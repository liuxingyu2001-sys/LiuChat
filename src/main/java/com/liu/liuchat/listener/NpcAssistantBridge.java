package com.liu.liuchat.listener;

import com.liu.liuchat.command.AssistantDialog;
import com.liu.liuchat.config.ConfigDefaults;
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

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** Optional Citizens right-click bridge; no Citizens classes are loaded when absent. */
public final class NpcAssistantBridge implements Listener {
    private record Binding(String assistant, String title) { }
    private final JavaPlugin plugin;
    private final AssistantDialog dialog;
    private volatile Map<Integer, Binding> bindings = Map.of();
    private final Map<java.util.UUID, Long> lastClick = new HashMap<>();
    private volatile double maxDistance = 6;
    private volatile boolean cancelOtherActions = true;

    public NpcAssistantBridge(JavaPlugin plugin, AssistantDialog dialog) {
        this.plugin = plugin;
        this.dialog = dialog;
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
                    dialog.open(player, binding.assistant(), binding.title());
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
        var config = ConfigDefaults.load(plugin, "npc-assistants.yml");
        maxDistance = Math.max(1, Math.min(16, config.getDouble("max-distance", 6)));
        cancelOtherActions = config.getBoolean("cancel-other-actions", true);
        bindings = parseBindings(config);
    }

    static Map<Integer, Binding> parseBindings(YamlConfiguration config) {
        Map<Integer, Binding> loaded = new HashMap<>();
        var nodes = config.getConfigurationSection("npcs");
        if (nodes != null) for (String key : nodes.getKeys(false)) {
            try {
                int id = Integer.parseInt(key);
                if (id < 0 || !nodes.getBoolean(key + ".enable", true)) continue;
                String assistant = nodes.getString(key + ".assistant", "");
                if (!assistant.matches("[a-zA-Z0-9_-]{1,48}")) continue;
                loaded.put(id, new Binding(assistant, nodes.getString(key + ".title", "聊天助手")));
            } catch (NumberFormatException ignored) { }
        }
        return Map.copyOf(loaded);
    }
}

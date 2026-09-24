package com.liu.liuchat.service;

import com.liu.liuchat.storage.Database;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/** Ignore lists persist per player; MySQL makes the list available after server transfers. */
public final class IgnoreService implements Listener {
    private final Database database;
    // 异步聊天/跨服消息线程会读，主线程会写，必须并发安全（写少读多用 COW 集合）
    private final Map<String, Set<String>> lists = new ConcurrentHashMap<>();

    public IgnoreService(Database database) { this.database = database; }

    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }
    private static String owner(Player player) { return player.getUniqueId().toString(); }

    private Set<String> list(Player player) {
        String uuid = owner(player);
        Set<String> cached = lists.get(uuid);
        if (cached != null) return cached;
        // JDBC 加载放在 map 之外，避免阻塞其他线程
        Set<String> loaded = new CopyOnWriteArraySet<>();
        for (String name : database.loadIgnores(uuid)) loaded.add(key(name));
        Set<String> prev = lists.putIfAbsent(uuid, loaded);
        return prev != null ? prev : loaded;
    }

    public boolean ignores(Player viewer, String senderUuid, String senderName) {
        return list(viewer).contains(key(senderName));
    }

    public boolean add(Player player, String name) {
        String value = key(name);
        if (!list(player).add(value)) return false;
        database.addIgnore(owner(player), value);
        return true;
    }

    public boolean remove(Player player, String name) {
        String value = key(name);
        if (!list(player).remove(value)) return false;
        database.removeIgnore(owner(player), value);
        return true;
    }

    public List<String> names(Player player) { return list(player).stream().sorted().toList(); }

    @EventHandler public void onJoin(PlayerJoinEvent event) { list(event.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent event) { lists.remove(owner(event.getPlayer())); }
}

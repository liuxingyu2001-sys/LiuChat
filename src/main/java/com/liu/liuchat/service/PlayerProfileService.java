package com.liu.liuchat.service;

import com.liu.liuchat.storage.Database;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Per-player nickname and selected chat color, shared via MySQL after server transfers. */
public final class PlayerProfileService implements Listener {
    private final Database database;
    // 聊天(异步事件)与跨服消息线程都会读，主线程会写，必须是并发安全的
    private final Map<String, Database.Profile> profiles = new ConcurrentHashMap<>();

    public PlayerProfileService(Database database) { this.database = database; }
    private static String id(Player player) { return player.getUniqueId().toString(); }
    public Database.Profile get(Player player) {
        String key = id(player);
        Database.Profile cached = profiles.get(key);
        if (cached != null) return cached;
        // 阻塞式 JDBC 加载放在 map 之外，避免持着桶锁做 IO
        Database.Profile loaded = database.loadProfile(key);
        if (loaded == null) loaded = new Database.Profile("", "");
        Database.Profile prev = profiles.putIfAbsent(key, loaded);
        return prev != null ? prev : loaded;
    }
    public void nick(Player player, String name) {
        Database.Profile old = get(player);
        update(player, new Database.Profile(name, old.color()));
    }
    public void color(Player player, String color) {
        Database.Profile old = get(player);
        update(player, new Database.Profile(old.nick(), color));
    }
    private void update(Player player, Database.Profile profile) {
        profiles.put(id(player), profile);
        database.saveProfile(id(player), profile);
    }
    @EventHandler public void onJoin(PlayerJoinEvent event) { get(event.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent event) { profiles.remove(id(event.getPlayer())); }
}

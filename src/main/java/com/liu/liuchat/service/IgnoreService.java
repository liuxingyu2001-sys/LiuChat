package com.liu.liuchat.service;

import com.liu.liuchat.storage.Database;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Ignore lists persist per player; MySQL makes the list available after server transfers. */
public final class IgnoreService implements Listener {
    private final Database database;
    private final Map<String, Set<String>> lists = new HashMap<>();

    public IgnoreService(Database database) { this.database = database; }

    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }
    private static String owner(Player player) { return player.getUniqueId().toString(); }

    private Set<String> list(Player player) {
        return lists.computeIfAbsent(owner(player), uuid -> new HashSet<>(database.loadIgnores(uuid)));
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

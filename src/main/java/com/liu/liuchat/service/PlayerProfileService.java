package com.liu.liuchat.service;

import com.liu.liuchat.storage.Database;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;

/** Per-player nickname and selected chat color, shared via MySQL after server transfers. */
public final class PlayerProfileService implements Listener {
    private final Database database;
    private final Map<String, Database.Profile> profiles = new HashMap<>();

    public PlayerProfileService(Database database) { this.database = database; }
    private static String id(Player player) { return player.getUniqueId().toString(); }
    public Database.Profile get(Player player) {
        return profiles.computeIfAbsent(id(player), database::loadProfile);
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

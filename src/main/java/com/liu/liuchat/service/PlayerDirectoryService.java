package com.liu.liuchat.service;

import com.liu.liuchat.model.PlayerIdentity;
import com.liu.liuchat.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 玩家名 → UUID 目录。好友/邮件允许按名字操作离线玩家时，必须先解析 UUID；
 * 目录在玩家加入/退出时更新，并在 MySQL 模式下跨子服共享。
 */
public final class PlayerDirectoryService implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final Database database;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "LiuChat-social-directory");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, PlayerIdentity> byName = new ConcurrentHashMap<>();
    private final Map<String, PlayerIdentity> byUuid = new ConcurrentHashMap<>();

    public PlayerDirectoryService(JavaPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        record(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        record(event.getPlayer());
    }

    /** 主线程调用；写库异步，不阻塞登录/退出。 */
    public void record(Player player) {
        if (player == null) return;
        PlayerIdentity identity = new PlayerIdentity(player.getUniqueId().toString(), player.getName(),
                System.currentTimeMillis());
        cache(identity);
        try {
            worker.execute(() -> database.saveIdentity(identity));
        } catch (RuntimeException ignored) {
            // 停服竞态：数据库关闭前已提交的写入由 DbExecutor 排空
        }
    }

    private void cache(PlayerIdentity identity) {
        String key = identity.name().toLowerCase(Locale.ROOT);
        byUuid.put(identity.uuid(), identity);
        byName.entrySet().removeIf(entry -> entry.getValue().uuid().equals(identity.uuid())
                && !entry.getKey().equals(key));
        byName.put(key, identity);
    }

    /** 本服在线优先，其次内存缓存，最后异步查库。 */
    public CompletableFuture<Optional<PlayerIdentity>> find(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_]{1,32}")) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            PlayerIdentity identity = new PlayerIdentity(online.getUniqueId().toString(), online.getName(),
                    System.currentTimeMillis());
            cache(identity);
            return CompletableFuture.completedFuture(Optional.of(identity));
        }
        PlayerIdentity cached = byName.get(name.toLowerCase(Locale.ROOT));
        if (cached != null) return CompletableFuture.completedFuture(Optional.of(cached));
        return CompletableFuture.supplyAsync(() -> {
            PlayerIdentity loaded = database.loadIdentityByName(name.toLowerCase(Locale.ROOT));
            if (loaded == null) return Optional.empty();
            cache(loaded);
            return Optional.of(loaded);
        }, worker);
    }

    public CompletableFuture<Optional<PlayerIdentity>> find(UUID uuid) {
        if (uuid == null) return CompletableFuture.completedFuture(Optional.empty());
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            PlayerIdentity identity = new PlayerIdentity(uuid.toString(), online.getName(),
                    System.currentTimeMillis());
            cache(identity);
            return CompletableFuture.completedFuture(Optional.of(identity));
        }
        PlayerIdentity cached = byUuid.get(uuid.toString());
        if (cached != null) return CompletableFuture.completedFuture(Optional.of(cached));
        return CompletableFuture.supplyAsync(() -> {
            PlayerIdentity loaded = database.loadIdentityByUuid(uuid.toString());
            if (loaded == null) return Optional.empty();
            cache(loaded);
            return Optional.of(loaded);
        }, worker);
    }

    /** 供通知层同步取显示名；未命中返回 fallback。 */
    public String nameOf(UUID uuid, String fallback) {
        if (uuid == null) return fallback == null || fallback.isBlank() ? "" : fallback;
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();
        PlayerIdentity cached = byUuid.get(uuid.toString());
        if (cached != null) return cached.name();
        return fallback == null || fallback.isBlank() ? uuid.toString() : fallback;
    }

    @Override
    public void close() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(3, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
    }
}

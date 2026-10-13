package com.liu.liuchat.service;

import com.liu.liuchat.api.ChannelApi;
import com.liu.liuchat.api.ChatChannel;
import com.liu.liuchat.config.ConfigDefaults;
import com.liu.liuchat.util.Schedulers;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.concurrent.*;

public final class ChannelService implements ChannelApi {
    private final JavaPlugin plugin;
    private record Configuration(Map<String, ChatChannel> channels, String defaultId) { }
    private volatile Configuration configuration;
    private final Map<String, ChatChannel> extensions = new ConcurrentHashMap<>();
    private final Map<UUID, String> selected = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> members = new ConcurrentHashMap<>();
    public interface Sender { Result send(Player player, String id, String text); }
    private volatile boolean closed;
    private final Set<CompletableFuture<Result>> pending = ConcurrentHashMap.newKeySet();
    private volatile Sender sender;
    public ChannelService(JavaPlugin plugin) { this.plugin = plugin; reload(); }
    public void setSender(Sender sender) { this.sender = sender; }
    public void reload() {
        var yaml = ConfigDefaults.load(plugin, "channels.yml");
        Map<String, ChatChannel> next = parse(yaml.getConfigurationSection("channels"));
        String id = normalize(yaml.getString("default", "global"));
        if (!next.containsKey(id)) throw new IllegalArgumentException("Default channel does not exist: " + id);
        configuration = new Configuration(next, id);
    }
    public static Map<String, ChatChannel> parse(ConfigurationSection root) {
        if (root == null) throw new IllegalArgumentException("Missing channels section");
        Map<String, ChatChannel> result = new LinkedHashMap<>();
        for (String key : root.getKeys(false)) {
            var c = root.getConfigurationSection(key);
            if (c == null) throw new IllegalArgumentException("Invalid channel: " + key);
            ChatChannel channel = new ChatChannel(key, c.getString("name", key),
                    ChatChannel.Scope.valueOf(c.getString("scope", "SERVER").toUpperCase(Locale.ROOT)),
                    c.getString("send-permission", ""), c.getString("receive-permission", ""),
                    c.getString("format", "&7[${channel_name}] &f${player}&7: ${message}"), c.getDouble("radius", 100));
            if (result.put(channel.id(), channel) != null) throw new IllegalArgumentException("Duplicate channel: " + key);
        }
        if (result.isEmpty()) throw new IllegalArgumentException("No channels configured");
        return Collections.unmodifiableMap(result);
    }
    private static String normalize(String id) { return id == null ? "" : id.toLowerCase(Locale.ROOT); }
    public Collection<ChatChannel> channels() {
        Map<String, ChatChannel> all = new TreeMap<>(extensions); all.putAll(configuration.channels()); return List.copyOf(all.values());
    }
    public Optional<ChatChannel> channel(String id) {
        String key = normalize(id); ChatChannel c = configuration.channels().get(key);
        return Optional.ofNullable(c == null ? extensions.get(key) : c);
    }
    public ChatChannel current(UUID player) {
        Configuration snapshot = configuration;
        String id = selected.get(player);
        ChatChannel c = id == null ? null : snapshot.channels().get(id);
        if (c == null && id != null) c = extensions.get(id);
        return c == null ? snapshot.channels().get(snapshot.defaultId()) : c;
    }
    public Set<String> subscriptions(UUID player) { return Set.copyOf(members.getOrDefault(player, Set.of())); }
    public boolean register(ChatChannel channel) { return !configuration.channels().containsKey(channel.id()) && extensions.putIfAbsent(channel.id(), channel) == null; }
    public boolean unregister(String id) { return extensions.remove(normalize(id)) != null; }
    private static void requireOwner(Player player) {
        boolean owned = Schedulers.isFolia() ? org.bukkit.Bukkit.isOwnedByCurrentRegion(player) : org.bukkit.Bukkit.isPrimaryThread();
        if (!owned) throw new IllegalStateException("Channel Player operations require the player's owning thread; use send() from async threads");
    }
    public Result access(Player player, ChatChannel c) {
        requireOwner(player);
        if (!player.isOnline()) return Result.OFFLINE;
        if (!allowed(player, c.sendPermission()) || !allowed(player, c.receivePermission())) return Result.NO_PERMISSION;
        if (c.scope() == ChatChannel.Scope.MEMBERS && !subscriptions(player.getUniqueId()).contains(c.id())) return Result.NOT_MEMBER;
        return Result.SENT;
    }
    private static boolean allowed(Player p, String permission) { return permission.isBlank() || p.hasPermission(permission); }
    public Result switchChannel(Player player, String id) {
        requireOwner(player);
        ChatChannel c = channel(id).orElse(null); if (c == null) return Result.UNKNOWN_CHANNEL;
        Result result = access(player, c);
        if (result != Result.SENT) return result;
        var event = new com.liu.liuchat.api.event.ChannelSwitchEvent(player, current(player.getUniqueId()), c);
        org.bukkit.Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return Result.CANCELLED;
        selected.put(player.getUniqueId(), c.id()); return Result.SENT;
    }
    public Result subscribe(Player player, String id) {
        requireOwner(player);
        if (!player.isOnline()) return Result.OFFLINE;
        ChatChannel c = channel(id).orElse(null); if (c == null) return Result.UNKNOWN_CHANNEL;
        if (!allowed(player, c.receivePermission())) return Result.NO_PERMISSION;
        members.computeIfAbsent(player.getUniqueId(), key -> ConcurrentHashMap.newKeySet()).add(c.id()); return Result.SENT;
    }
    public boolean unsubscribe(UUID player, String id) {
        String key = normalize(id); Set<String> set = members.get(player);
        boolean removed = set != null && set.remove(key); selected.remove(player, key); return removed;
    }
    public CompletableFuture<Result> send(Player player, String id, String message) {
        Objects.requireNonNull(player); Objects.requireNonNull(message);
        var result = new CompletableFuture<Result>();
        if (closed || !plugin.isEnabled()) { result.complete(Result.CANCELLED); return result; }
        pending.add(result);
        result.whenComplete((value, error) -> pending.remove(result));
        if (!player.isOnline()) { result.complete(Result.OFFLINE); return result; }
        // Retirement callback guarantees callers are not left waiting after disconnect on Folia.
        Runnable action = () -> {
            try { result.complete(sender == null ? Result.CANCELLED : sender.send(player, id, message)); }
            catch (Throwable t) { result.completeExceptionally(t); }
        };
        try {
            if (Schedulers.isFolia()) {
                if (!player.getScheduler().execute(plugin, action, () -> result.complete(Result.OFFLINE), 1)) result.complete(Result.OFFLINE);
            } else Schedulers.runFor(plugin, player, action);
        } catch (RuntimeException e) { result.completeExceptionally(e); }
        return result;
    }
    public void forget(UUID uuid) { selected.remove(uuid); members.remove(uuid); }
    public void close() {
        closed = true; sender = null;
        pending.forEach(f -> f.complete(Result.CANCELLED)); pending.clear();
        extensions.clear(); selected.clear(); members.clear();
    }
}

package com.liu.liuchat.service;

import com.liu.liuchat.api.Friend;
import com.liu.liuchat.api.FriendApi;
import com.liu.liuchat.api.FriendRequest;
import com.liu.liuchat.api.FriendResult;
import com.liu.liuchat.api.event.FriendAddedEvent;
import com.liu.liuchat.api.event.FriendRemovedEvent;
import com.liu.liuchat.api.event.FriendRequestEvent;
import com.liu.liuchat.config.ConfigManager;
import com.liu.liuchat.config.MessageManager;
import com.liu.liuchat.hook.HuskHomesHook;
import com.liu.liuchat.model.FriendRequestRow;
import com.liu.liuchat.model.FriendRow;
import com.liu.liuchat.storage.Database;
import com.liu.liuchat.util.Schedulers;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * 好友服务：双向关系持久化、请求过期、上限、上线通知与 API。
 * 所有数据库操作在专用线程执行；事件统一回主线程触发。
 */
public final class FriendService implements FriendApi, Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final Database database;
    private final ConfigManager config;
    private final CrossServerService crossServer;
    private final PlayerDirectoryService directory;
    private final SocialNotifier notifier;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "LiuChat-friends");
        thread.setDaemon(true);
        return thread;
    });
    private Schedulers.Handle purgeTask;
    private volatile boolean closed;
    /** 接收者 + 上线好友 -> 最近一次上线提醒时间，避免跨服往返重复提醒。 */
    private final FriendJoinNotificationGate joinGate = new FriendJoinNotificationGate();
    /** 发起者 -> 好友传送冷却结束时间戳。 */
    private final Map<UUID, Long> teleportCooldowns = new ConcurrentHashMap<>();

    public FriendService(JavaPlugin plugin, Database database, ConfigManager config, MessageManager messages,
                         CrossServerService crossServer, PlayerDirectoryService directory) {
        this.plugin = plugin;
        this.database = database;
        this.config = config;
        this.crossServer = crossServer;
        this.directory = directory;
        this.notifier = new SocialNotifier(plugin, messages, crossServer);
    }

    public void start() {
        purgeTask = Schedulers.runAsyncTimer(plugin, () -> {
            if (database.isReady()) database.removeExpiredFriendRequests(System.currentTimeMillis());
        }, 1200L, 72_000L);
    }

    @Override
    public boolean isEnabled() {
        return !closed && config.friendsEnabled();
    }

    @Override
    public CompletableFuture<List<Friend>> friends(UUID player) {
        if (!isEnabled() || player == null) return CompletableFuture.completedFuture(List.of());
        return CompletableFuture.supplyAsync(() -> {
            List<FriendRow> rows = database.loadFriends(player.toString());
            if (rows == null) return List.<Friend>of();
            return rows.stream().map(row -> mapFriend(row)).toList();
        }, worker).exceptionally(error -> {
            log("读取好友列表失败", error);
            return List.of();
        });
    }

    @Override
    public CompletableFuture<Optional<Friend>> friend(UUID player, UUID other) {
        if (player == null || other == null) return CompletableFuture.completedFuture(Optional.empty());
        return friends(player).thenApply(list -> list.stream()
                .filter(friend -> friend.uuid().equals(other)).findFirst());
    }

    private Friend mapFriend(FriendRow row) {
        UUID uuid = UUID.fromString(row.uuid());
        String name = directory.nameOf(uuid, row.name());
        return new Friend(uuid, name, row.since(), isOnline(name));
    }

    @Override
    public CompletableFuture<List<FriendRequest>> incomingRequests(UUID player) {
        if (!isEnabled() || player == null) return CompletableFuture.completedFuture(List.of());
        return requestsIncoming(player);
    }

    private CompletableFuture<List<FriendRequest>> requestsIncoming(UUID player) {
        return CompletableFuture.supplyAsync(() -> mapRequests(database.loadIncomingRequests(player.toString(),
                System.currentTimeMillis())), worker).exceptionally(error -> {
            log("读取好友请求失败", error);
            return List.of();
        });
    }

    @Override
    public CompletableFuture<List<FriendRequest>> outgoingRequests(UUID player) {
        if (!isEnabled() || player == null) return CompletableFuture.completedFuture(List.of());
        return CompletableFuture.supplyAsync(() -> mapRequests(database.loadOutgoingRequests(player.toString(),
                System.currentTimeMillis())), worker).exceptionally(error -> {
            log("读取好友请求失败", error);
            return List.of();
        });
    }

    private List<FriendRequest> mapRequests(List<FriendRequestRow> rows) {
        if (rows == null) return List.of();
        return rows.stream().map(row -> {
            UUID sender = UUID.fromString(row.senderUuid());
            UUID target = UUID.fromString(row.targetUuid());
            return new FriendRequest(sender, directory.nameOf(sender, row.senderName()), target,
                    directory.nameOf(target, row.targetName()), row.createdAt(), row.expiresAt());
        }).toList();
    }

    @Override
    public CompletableFuture<FriendResult> request(UUID sender, String senderName, UUID target, String targetName) {
        if (!isEnabled()) return CompletableFuture.completedFuture(FriendResult.DISABLED);
        if (sender == null || target == null) return CompletableFuture.completedFuture(FriendResult.NOT_FOUND);
        if (sender.equals(target)) return CompletableFuture.completedFuture(FriendResult.SELF);
        if (!database.isReady()) return CompletableFuture.completedFuture(FriendResult.STORAGE_UNAVAILABLE);
        String senderDisplay = display(senderName, sender);
        String targetDisplay = display(targetName, target);
        long now = System.currentTimeMillis();
        long expires = expiresAt(now);
        FriendRequestEvent event = new FriendRequestEvent(sender, senderDisplay, target, targetDisplay, expires);
        SocialEvents.fire(plugin, event);
        if (event.isCancelled()) return CompletableFuture.completedFuture(FriendResult.CANCELLED);
        long eventExpires = event.getExpiresAt();
        return CompletableFuture.supplyAsync(() -> requestNow(sender, senderDisplay, target, targetDisplay,
                now, eventExpires), worker).exceptionally(error -> {
            log("发送好友请求失败", error);
            return FriendResult.STORAGE_UNAVAILABLE;
        });
    }

    private FriendResult requestNow(UUID sender, String senderName, UUID target, String targetName,
                                    long now, long expiresAt) {
        if (!database.isReady()) return FriendResult.STORAGE_UNAVAILABLE;
        if (database.areFriends(sender.toString(), target.toString())) return FriendResult.ALREADY_FRIENDS;
        if (friendsFull(sender) || friendsFull(target)) return FriendResult.FRIENDS_FULL;
        if (requestLimitReached(sender, now) || incomingLimitReached(target, now)) {
            return FriendResult.REQUEST_LIMIT;
        }

        FriendRequestRow reverse = database.loadFriendRequest(target.toString(), sender.toString());
        if (reverse != null && !reverse.expired(now)) {
            FriendResult accepted = addFriends(sender, senderName, target, targetName);
            database.removeFriendRequestsBetween(sender.toString(), target.toString());
            if (accepted == FriendResult.OK) {
                notifier.sendClickable(targetName, "friend.accepted-notify", "/liuc friend", "${player}", senderName);
                return FriendResult.ACCEPTED_MUTUAL;
            }
            return accepted;
        }
        FriendRequestRow existing = database.loadFriendRequest(sender.toString(), target.toString());
        if (existing != null && !existing.expired(now)) {
            return FriendResult.REQUEST_EXISTS;
        }
        if (existing != null) database.removeFriendRequest(sender.toString(), target.toString());
        FriendRequestRow request = new FriendRequestRow(sender.toString(), senderName, target.toString(),
                targetName, now, expiresAt);
        if (!database.addFriendRequest(request)) {
            return database.areFriends(sender.toString(), target.toString())
                    ? FriendResult.ALREADY_FRIENDS : FriendResult.REQUEST_EXISTS;
        }
        notifier.sendClickable(targetName, "friend.request-received", "/liuc friend requests", "${player}", senderName);
        return FriendResult.REQUEST_SENT;
    }

    @Override
    public CompletableFuture<FriendResult> accept(UUID player, String playerName, UUID requester,
                                                  String requesterName) {
        if (!isEnabled()) return CompletableFuture.completedFuture(FriendResult.DISABLED);
        if (player == null || requester == null) return CompletableFuture.completedFuture(FriendResult.NOT_FOUND);
        if (player.equals(requester)) return CompletableFuture.completedFuture(FriendResult.SELF);
        if (!database.isReady()) return CompletableFuture.completedFuture(FriendResult.STORAGE_UNAVAILABLE);
        return CompletableFuture.supplyAsync(() -> acceptNow(player, playerName, requester, requesterName), worker)
                .exceptionally(error -> {
                    log("接受好友请求失败", error);
                    return FriendResult.STORAGE_UNAVAILABLE;
                });
    }

    private FriendResult acceptNow(UUID player, String playerName, UUID requester, String requesterName) {
        FriendRequestRow request = database.loadFriendRequest(requester.toString(), player.toString());
        if (request == null) {
            return database.areFriends(player.toString(), requester.toString())
                    ? FriendResult.ALREADY_FRIENDS : FriendResult.REQUEST_NOT_FOUND;
        }
        if (request.expired(System.currentTimeMillis())) {
            database.removeFriendRequest(requester.toString(), player.toString());
            return FriendResult.REQUEST_NOT_FOUND;
        }
        if (friendsFull(player) || friendsFull(requester)) return FriendResult.FRIENDS_FULL;
        FriendResult result = addFriends(player, display(playerName, player), requester,
                display(requesterName, requester));
        database.removeFriendRequestsBetween(player.toString(), requester.toString());
        if (result == FriendResult.OK) {
            notifier.sendClickable(display(requesterName, requester), "friend.accepted-notify", "/liuc friend",
                    "${player}", display(playerName, player));
        }
        return result;
    }

    @Override
    public CompletableFuture<FriendResult> deny(UUID player, UUID requester) {
        if (!isEnabled()) return CompletableFuture.completedFuture(FriendResult.DISABLED);
        if (player == null || requester == null) return CompletableFuture.completedFuture(FriendResult.NOT_FOUND);
        if (!database.isReady()) return CompletableFuture.completedFuture(FriendResult.STORAGE_UNAVAILABLE);
        return CompletableFuture.supplyAsync(() -> denyNow(player, directory.nameOf(player, player.toString()),
                requester, directory.nameOf(requester, requester.toString())), worker)
                .exceptionally(error -> operationalFailure("拒绝好友请求失败", error));
    }

    @Override
    public CompletableFuture<FriendResult> cancel(UUID sender, UUID target) {
        if (!isEnabled()) return CompletableFuture.completedFuture(FriendResult.DISABLED);
        if (sender == null || target == null) return CompletableFuture.completedFuture(FriendResult.NOT_FOUND);
        if (!database.isReady()) return CompletableFuture.completedFuture(FriendResult.STORAGE_UNAVAILABLE);
        return CompletableFuture.supplyAsync(() -> cancelNow(sender,
                directory.nameOf(sender, sender.toString()), target,
                directory.nameOf(target, target.toString())), worker)
                .exceptionally(error -> operationalFailure("撤回好友请求失败", error));
    }

    @Override
    public CompletableFuture<FriendResult> remove(UUID player, UUID friend) {
        if (!isEnabled()) return CompletableFuture.completedFuture(FriendResult.DISABLED);
        if (player == null || friend == null) return CompletableFuture.completedFuture(FriendResult.NOT_FOUND);
        if (!database.isReady()) return CompletableFuture.completedFuture(FriendResult.STORAGE_UNAVAILABLE);
        return CompletableFuture.supplyAsync(() -> removeNow(player,
                directory.nameOf(player, player.toString()), friend,
                directory.nameOf(friend, friend.toString())), worker)
                .exceptionally(error -> operationalFailure("删除好友失败", error));
    }

    /** 命令按名字拒绝：从收到的请求里匹配，兼容改名/离线玩家。 */
    public CompletableFuture<FriendResult> denyName(UUID player, String playerName, String requesterName) {
        if (!isEnabled()) return CompletableFuture.completedFuture(FriendResult.DISABLED);
        if (!database.isReady()) return CompletableFuture.completedFuture(FriendResult.STORAGE_UNAVAILABLE);
        return CompletableFuture.supplyAsync(() -> {
            FriendRequestRow request = findByName(database.loadIncomingRequests(player.toString(),
                    System.currentTimeMillis()), requesterName, false);
            if (request == null) return FriendResult.REQUEST_NOT_FOUND;
            return denyNow(player, playerName, UUID.fromString(request.senderUuid()), request.senderName());
        }, worker).exceptionally(error -> operationalFailure("拒绝好友请求失败", error));
    }

    public CompletableFuture<FriendResult> acceptName(UUID player, String playerName, String requesterName) {
        if (!isEnabled()) return CompletableFuture.completedFuture(FriendResult.DISABLED);
        if (!database.isReady()) return CompletableFuture.completedFuture(FriendResult.STORAGE_UNAVAILABLE);
        return CompletableFuture.supplyAsync(() -> {
            FriendRequestRow request = findByName(database.loadIncomingRequests(player.toString(),
                    System.currentTimeMillis()), requesterName, false);
            if (request == null) return FriendResult.REQUEST_NOT_FOUND;
            return acceptNow(player, playerName, UUID.fromString(request.senderUuid()), request.senderName());
        }, worker).exceptionally(error -> operationalFailure("接受好友请求失败", error));
    }

    public CompletableFuture<FriendResult> cancelName(UUID player, String playerName, String targetName) {
        if (!isEnabled()) return CompletableFuture.completedFuture(FriendResult.DISABLED);
        if (!database.isReady()) return CompletableFuture.completedFuture(FriendResult.STORAGE_UNAVAILABLE);
        return CompletableFuture.supplyAsync(() -> {
            FriendRequestRow request = findByName(database.loadOutgoingRequests(player.toString(),
                    System.currentTimeMillis()), targetName, true);
            if (request == null) return FriendResult.REQUEST_NOT_FOUND;
            return cancelNow(player, playerName, UUID.fromString(request.targetUuid()), request.targetName());
        }, worker).exceptionally(error -> operationalFailure("撤回好友请求失败", error));
    }

    public CompletableFuture<FriendResult> removeName(UUID player, String playerName, String friendName) {
        if (!isEnabled()) return CompletableFuture.completedFuture(FriendResult.DISABLED);
        if (!database.isReady()) return CompletableFuture.completedFuture(FriendResult.STORAGE_UNAVAILABLE);
        return CompletableFuture.supplyAsync(() -> {
            List<FriendRow> rows = database.loadFriends(player.toString());
            if (rows != null) {
                for (FriendRow row : rows) {
                    UUID uuid = UUID.fromString(row.uuid());
                    String current = directory.nameOf(uuid, row.name());
                    if (row.name().equalsIgnoreCase(friendName) || current.equalsIgnoreCase(friendName)) {
                        return removeNow(player, playerName, uuid, current);
                    }
                }
            }
            return FriendResult.NOT_FRIENDS;
        }, worker).exceptionally(error -> operationalFailure("删除好友失败", error));
    }

    private FriendResult denyNow(UUID player, String playerName, UUID requester, String requesterName) {
        if (!database.removeFriendRequest(requester.toString(), player.toString())) {
            return FriendResult.REQUEST_NOT_FOUND;
        }
        notifier.sendClickable(requesterName, "friend.denied-notify", "/liuc friend", "${player}", playerName);
        return FriendResult.OK;
    }

    private FriendResult cancelNow(UUID sender, String senderName, UUID target, String targetName) {
        if (!database.removeFriendRequest(sender.toString(), target.toString())) {
            return FriendResult.REQUEST_NOT_FOUND;
        }
        notifier.sendClickable(targetName, "friend.cancel-notify", "/liuc friend", "${player}", senderName);
        return FriendResult.OK;
    }

    private FriendResult removeNow(UUID player, String playerName, UUID friend, String friendName) {
        if (!database.areFriends(player.toString(), friend.toString())) {
            return FriendResult.NOT_FRIENDS;
        }
        if (!database.removeFriend(player.toString(), friend.toString())) {
            return FriendResult.NOT_FRIENDS;
        }
        database.removeFriendRequestsBetween(player.toString(), friend.toString());
        SocialEvents.fire(plugin, new FriendRemovedEvent(player, playerName, friend, friendName));
        notifier.sendClickable(friendName, "friend.removed-notify", "/liuc friend", "${player}", playerName);
        return FriendResult.OK;
    }

    private FriendResult operationalFailure(String message, Throwable error) {
        log(message, error);
        return FriendResult.STORAGE_UNAVAILABLE;
    }

    /** 在请求列表里按另一端名字匹配。outgoing=true 匹配 target，否则匹配 sender。 */
    private FriendRequestRow findByName(List<FriendRequestRow> rows, String name, boolean outgoing) {
        if (rows == null || name == null) return null;
        for (FriendRequestRow row : rows) {
            String stored = outgoing ? row.targetName() : row.senderName();
            String uuid = outgoing ? row.targetUuid() : row.senderUuid();
            String current = directory.nameOf(UUID.fromString(uuid), stored);
            if ((stored != null && stored.equalsIgnoreCase(name)) || current.equalsIgnoreCase(name)) return row;
        }
        return null;
    }

    @Override
    public CompletableFuture<FriendResult> teleport(UUID playerId, UUID friendId) {
        if (playerId == null || friendId == null) {
            return CompletableFuture.completedFuture(FriendResult.NOT_FOUND);
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return CompletableFuture.completedFuture(FriendResult.TELEPORT_OFFLINE);
        }
        return teleport(player, friendId, directory.nameOf(friendId, friendId.toString()));
    }

    /**
     * 好友传送：不需要对方同意，直接把发起者传送到在线好友身边。
     *
     * <p>只支持同一服务器：好友在其它子服时返回 {@link FriendResult#TELEPORT_OTHER_SERVER}，
     * 由界面提示玩家；是否启用、冷却秒数见 {@code friends.teleport} 与
     * {@code friends.teleport-cooldown-seconds}，权限为 {@code liuchat.friend.teleport}。</p>
     */
    public CompletableFuture<FriendResult> teleport(Player player, UUID friendId, String friendName) {
        if (closed || !config.friendsEnabled()) {
            return CompletableFuture.completedFuture(FriendResult.DISABLED);
        }
        if (!config.friendsTeleport()) {
            return CompletableFuture.completedFuture(FriendResult.TELEPORT_DISABLED);
        }
        if (player == null || friendId == null) {
            return CompletableFuture.completedFuture(FriendResult.NOT_FOUND);
        }
        if (player.getUniqueId().equals(friendId)) {
            return CompletableFuture.completedFuture(FriendResult.SELF);
        }
        if (!player.hasPermission("liuchat.friend.teleport")) {
            return CompletableFuture.completedFuture(FriendResult.TELEPORT_DENIED);
        }
        if (!database.isReady()) {
            return CompletableFuture.completedFuture(FriendResult.STORAGE_UNAVAILABLE);
        }
        return CompletableFuture.supplyAsync(() -> {
            if (!database.areFriends(player.getUniqueId().toString(), friendId.toString())) {
                return FriendResult.NOT_FRIENDS;
            }
            Player target = Bukkit.getPlayer(friendId);
            if (target != null && target.isOnline()) return FriendResult.OK;
            if (!isOnline(friendName)) return FriendResult.TELEPORT_OFFLINE;
            // 好友在其它子服：装了 HuskHomes 就交给它做跨服传送（它自己会走代理），
            // 否则只能提示“在其他服务器”。
            return config.friendsTeleportCrossServer() && HuskHomesHook.available()
                    ? FriendResult.TELEPORT_CROSS_SERVER
                    : FriendResult.TELEPORT_OTHER_SERVER;
        }, worker).thenCompose(status -> {
            if (status != FriendResult.OK && status != FriendResult.TELEPORT_CROSS_SERVER) {
                return CompletableFuture.completedFuture(status);
            }
            long now = System.currentTimeMillis();
            long cooldown = Math.max(0L, config.friendsTeleportCooldownSeconds() * 1000L);
            if (cooldown > 0L) {
                Long until = teleportCooldowns.get(player.getUniqueId());
                if (until != null && until > now) {
                    return CompletableFuture.completedFuture(FriendResult.TELEPORT_COOLDOWN);
                }
                teleportCooldowns.put(player.getUniqueId(), now + cooldown);
            }
            if (status == FriendResult.TELEPORT_CROSS_SERVER) {
                return HuskHomesHook.teleportToPlayer(plugin, player, friendName)
                        .thenApply(ok -> ok ? FriendResult.TELEPORT_CROSS_SERVER : FriendResult.TELEPORT_FAILED);
            }
            Player target = Bukkit.getPlayer(friendId);
            if (target == null || !target.isOnline()) {
                return CompletableFuture.completedFuture(FriendResult.TELEPORT_OFFLINE);
            }
            return player.teleportAsync(target.getLocation())
                    .thenApply(ok -> {
                        if (!Boolean.TRUE.equals(ok)) return FriendResult.TELEPORT_FAILED;
                        notifier.send(target.getName(), "friend.teleport-target-notify",
                                "${player}", player.getName());
                        return FriendResult.OK;
                    });
        }).exceptionally(error -> {
            log("好友传送失败", error);
            return FriendResult.TELEPORT_FAILED;
        });
    }

    @Override
    public CompletableFuture<Boolean> areFriends(UUID first, UUID second) {
        if (first == null || second == null || !isEnabled()) return CompletableFuture.completedFuture(false);
        return CompletableFuture.supplyAsync(() -> database.areFriends(first.toString(), second.toString()),
                worker).exceptionally(error -> false);
    }

    @Override
    public boolean isOnline(String playerName) {
        if (closed || playerName == null || playerName.isBlank()) return false;
        if (Bukkit.getPlayerExact(playerName) != null) return true;
        if (crossServer == null || !crossServer.isEnabled()) return false;
        return crossServer.knownPlayerNames().stream().anyMatch(name -> name.equalsIgnoreCase(playerName));
    }

    private FriendResult addFriends(UUID player, String playerName, UUID friend, String friendName) {
        String[] pair = player.toString().compareTo(friend.toString()) <= 0
                ? new String[]{player.toString(), friend.toString()}
                : new String[]{friend.toString(), player.toString()};
        String lowName = pair[0].equals(player.toString()) ? playerName : friendName;
        String highName = pair[1].equals(player.toString()) ? playerName : friendName;
        long now = System.currentTimeMillis();
        if (!database.addFriend(pair[0], lowName, pair[1], highName, now)) {
            return database.areFriends(player.toString(), friend.toString())
                    ? FriendResult.ALREADY_FRIENDS : FriendResult.STORAGE_UNAVAILABLE;
        }
        SocialEvents.fire(plugin, new FriendAddedEvent(player, playerName, friend, friendName, now));
        return FriendResult.OK;
    }

    private boolean friendsFull(UUID player) {
        int max = config.friendsMax();
        if (max <= 0) return false;
        List<FriendRow> rows = database.loadFriends(player.toString());
        return rows != null && rows.size() >= max;
    }

    private boolean requestLimitReached(UUID player, long now) {
        int max = config.friendsMaxRequests();
        if (max <= 0) return false;
        List<FriendRequestRow> rows = database.loadOutgoingRequests(player.toString(), now);
        return rows != null && rows.size() >= max;
    }

    private boolean incomingLimitReached(UUID player, long now) {
        int max = config.friendsMaxRequests();
        if (max <= 0) return false;
        List<FriendRequestRow> rows = database.loadIncomingRequests(player.toString(), now);
        return rows != null && rows.size() >= max;
    }

    private long expiresAt(long now) {
        int hours = config.friendsRequestExpireHours();
        return hours <= 0 ? Long.MAX_VALUE : now + hours * 3_600_000L;
    }

    private String display(String name, UUID uuid) {
        return directory.nameOf(uuid, name);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!isEnabled() || !config.friendsNotifyOnJoin() || !database.isReady()) return;
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long cooldown = Math.max(0L, config.friendsJoinNotifyCooldownSeconds() * 1000L);
        // 共享 last_seen：刚在别的子服切过来/重连时，不再向所有好友重复播报。
        directory.previousSeen(uuid).thenAccept(previous -> {
            if (previous > 0L && now - previous < cooldown) return;
            CompletableFuture.supplyAsync(() -> database.loadFriends(uuid.toString()), worker)
                    .thenAccept(rows -> {
                        if (rows == null) return;
                        for (FriendRow row : rows) {
                            if (!joinGate.shouldNotify(row.uuid(), uuid.toString(), now, cooldown)) continue;
                            notifier.sendClickable(row.name(), "friend.join-notify", "/liuc friend",
                                    "${player}", player.getName());
                        }
                    });
        });
        CompletableFuture.supplyAsync(() -> database.loadIncomingRequests(uuid.toString(), now), worker)
                .thenAccept(rows -> {
                    if (rows != null && !rows.isEmpty()) {
                        notifier.sendClickable(player.getName(), "friend.join-requests", "/liuc friend requests",
                                "${count}", String.valueOf(rows.size()));
                    }
                });
    }

    private void log(String message, Throwable error) {
        plugin.getLogger().log(Level.WARNING, message, error);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (purgeTask != null) purgeTask.cancel();
        joinGate.clear();
        teleportCooldowns.clear();
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
    }
}

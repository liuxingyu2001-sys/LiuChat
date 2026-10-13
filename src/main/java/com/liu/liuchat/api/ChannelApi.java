package com.liu.liuchat.api;

import org.bukkit.entity.Player;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Obtain through Bukkit ServicesManager. Query methods are thread-safe; Player mutations require its owning thread. */
public interface ChannelApi {
    enum Result { SENT, UNKNOWN_CHANNEL, NO_PERMISSION, NOT_MEMBER, MUTED, COOLDOWN, REPEATED, FILTERED, OFFLINE, CANCELLED }
    Collection<ChatChannel> channels();
    Optional<ChatChannel> channel(String id);
    ChatChannel current(UUID player);
    Set<String> subscriptions(UUID player);
    boolean register(ChatChannel channel);
    boolean unregister(String id);
    Result switchChannel(Player player, String id);
    Result subscribe(Player player, String id);
    boolean unsubscribe(UUID player, String id);
    /** Safe from any thread. Schedules on the sender region; completes when accepted locally, not remote ACK. */
    CompletableFuture<Result> send(Player sender, String channel, String message);
}

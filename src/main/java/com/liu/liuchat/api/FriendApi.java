package com.liu.liuchat.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 好友系统公开 API。通过 Bukkit {@code ServicesManager} 获取：
 *
 * <pre>{@code
 * FriendApi friends = Bukkit.getServicesManager().load(FriendApi.class);
 * friends.request(senderId, senderName, targetId, targetName)
 *        .thenAccept(result -> ...);
 * }</pre>
 *
 * <p>所有查询/修改返回 {@link CompletableFuture}，不会阻塞服务器主线程；回调可能在工作线程。
 * 涉及 Bukkit 玩家对象的操作请自行切回该玩家区域线程（或调用命令层已封装的接口）。
 * 可取消事件在调用线程上同步触发；命令调用位于主线程，外部异步调用会等待主线程完成事件。
 */
public interface FriendApi {

    /** 好友列表（含当前在线状态）。 */
    CompletableFuture<List<Friend>> friends(UUID player);

    CompletableFuture<Optional<Friend>> friend(UUID player, UUID other);

    /** 收到但尚未处理的请求。 */
    CompletableFuture<List<FriendRequest>> incomingRequests(UUID player);

    /** 自己发出、尚未被处理的请求。 */
    CompletableFuture<List<FriendRequest>> outgoingRequests(UUID player);

    /**
     * 发送好友请求。
     *
     * @param senderName 发送者显示名（用于通知与数据库快照）
     * @param targetName 接收者显示名
     */
    CompletableFuture<FriendResult> request(UUID sender, String senderName, UUID target, String targetName);

    /** 接受请求；玩家与请求者名字用于通知。 */
    CompletableFuture<FriendResult> accept(UUID player, String playerName, UUID requester, String requesterName);

    CompletableFuture<FriendResult> deny(UUID player, UUID requester);

    /** 撤回自己发出的请求。 */
    CompletableFuture<FriendResult> cancel(UUID sender, UUID target);

    CompletableFuture<FriendResult> remove(UUID player, UUID friend);

    CompletableFuture<Boolean> areFriends(UUID first, UUID second);

    /**
     * 好友传送：不需要对方同意，把在线玩家直接传送到在线好友身边。
     * <p>同服直接落地；好友在其它子服时，若服务器装了 HuskHomes 且开启跨服模式，
     * 会把传送交给 HuskHomes 完成，返回 {@link FriendResult#TELEPORT_CROSS_SERVER}
     * （异步，玩家稍后到达）。好友离线返回 {@link FriendResult#TELEPORT_OFFLINE}，
     * 其它子服且无法跨服传送返回 {@link FriendResult#TELEPORT_OTHER_SERVER}。</p>
     */
    CompletableFuture<FriendResult> teleport(UUID player, UUID friend);

    /** 本服精确在线，或跨服在线名单中包含该名字。 */
    boolean isOnline(String playerName);

    boolean isEnabled();
}

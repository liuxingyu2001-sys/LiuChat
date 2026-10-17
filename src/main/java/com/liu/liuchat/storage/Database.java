package com.liu.liuchat.storage;

import com.liu.liuchat.model.FriendRequestRow;
import com.liu.liuchat.model.FriendRow;
import com.liu.liuchat.model.MailRow;
import com.liu.liuchat.model.MuteData;
import com.liu.liuchat.model.PlayerIdentity;

import java.util.List;

/**
 * 存储层接口：SQLite（单服）/ MySQL（跨服共享）双实现，
 * 由 {@link DatabaseFactory} 按 config.yml 的 storage.type 选择。
 * <p>
 * 并发语义：写操作异步落库（FIFO 单线程，插件关闭前排空），
 * 读操作同步返回并保证看到此前所有已入队的写（读己之写）。
 * <p>
 * 实现必须保证：初始化失败时降级为"仅内存"（isReady()=false），不阻塞插件启用。
 */
public interface Database extends AutoCloseable {

    boolean isReady();

    List<MuteData> loadMutes();

    void saveMute(MuteData mute);

    void deleteMute(String uuid);

    record Profile(String nick, String color) { }

    Profile loadProfile(String owner);

    void saveProfile(String owner, Profile profile);

    List<String> loadIgnores(String owner);

    void addIgnore(String owner, String name);

    void removeIgnore(String owner, String name);

    /** Returns -1 when storage is unavailable. */
    int hornBalance(String owner);

    /** Atomically adds credits; returns -1 on failure. */
    int addHorns(String owner, int amount);

    /** Atomically spends one credit. */
    boolean spendHorn(String owner);

    // ---------------- 玩家名目录 ----------------

    /** 离线按名字查找好友/邮件收件人用的 UUID 目录；写入异步、读取同步。 */
    default void saveIdentity(PlayerIdentity identity) {
    }

    /** @param nameKey 小写名字 */
    default PlayerIdentity loadIdentityByName(String nameKey) {
        return null;
    }

    default PlayerIdentity loadIdentityByUuid(String uuid) {
        return null;
    }

    // ---------------- 好友 ----------------

    default List<FriendRow> loadFriends(String uuid) {
        return List.of();
    }

    default boolean areFriends(String first, String second) {
        return false;
    }

    /**
     * 写入一条双向好友关系。
     *
     * @param lowUuid 字典序较小的 UUID；名称/since 与之对应
     * @return true = 新写入；false = 已存在或写入失败
     */
    default boolean addFriend(String lowUuid, String lowName, String highUuid, String highName, long since) {
        return false;
    }

    default boolean removeFriend(String first, String second) {
        return false;
    }

    default List<FriendRequestRow> loadIncomingRequests(String uuid, long now) {
        return List.of();
    }

    default List<FriendRequestRow> loadOutgoingRequests(String uuid, long now) {
        return List.of();
    }

    default FriendRequestRow loadFriendRequest(String senderUuid, String targetUuid) {
        return null;
    }

    default boolean addFriendRequest(FriendRequestRow request) {
        return false;
    }

    default boolean removeFriendRequest(String senderUuid, String targetUuid) {
        return false;
    }

    /** 接受好友时把两个方向的请求一起清掉。 */
    default int removeFriendRequestsBetween(String first, String second) {
        return 0;
    }

    default int removeExpiredFriendRequests(long now) {
        return 0;
    }

    // ---------------- 邮件 ----------------

    default boolean saveMail(MailRow mail) {
        return false;
    }

    default MailRow loadMail(String id) {
        return null;
    }

    /** 按唯一前缀查找收件人的邮件；找不到返回 null。 */
    default MailRow loadMailByPrefix(String recipientUuid, String prefix) {
        return null;
    }

    /** 按创建时间倒序分页；offset 从 0 开始，已过期邮件不返回。 */
    default List<MailRow> loadMail(String recipientUuid, long now, int offset, int limit) {
        return List.of();
    }

    default int countMail(String recipientUuid, long now) {
        return 0;
    }

    default int countUnread(String recipientUuid, long now) {
        return 0;
    }

    default boolean markMailRead(String id, String recipientUuid) {
        return false;
    }

    default boolean markMailClaimed(String id, String recipientUuid) {
        return false;
    }

    default boolean deleteMail(String id, String recipientUuid) {
        return false;
    }

    default int deleteExpiredMail(long now) {
        return 0;
    }

    @Override
    void close();
}

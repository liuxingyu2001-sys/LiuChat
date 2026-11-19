package com.liu.liuchat.service;

import com.liu.liuchat.api.Friend;
import com.liu.liuchat.config.MessageManager;
import org.bukkit.entity.Player;

import java.time.ZoneId;

/** 好友/邮件界面入口；命令与点击通知只通过这里打开 GUI。 */
public interface SocialMenu {
    void openFriendMain(Player player);

    void openFriendRequests(Player player, int page);

    void openFriendSent(Player player, int page);

    void openAddFriendDialog(Player player);

    void openMailList(Player player);

    void openCompose(Player player);

    void closeAll();

    /**
     * 好友列表 lore：离线好友的“最近在线”行；没有记录时显示未知。
     * 模板取 {@code friend.last-seen} / {@code friend.last-seen-unknown}，
     * 时间按服务器时区格式化（今年省略年份，跨年带年份，见 {@link FriendTimeText}）。
     */
    static String lastSeenLine(MessageManager messages, Friend friend) {
        if (friend.lastSeen() <= 0) return messages.get("friend.last-seen-unknown");
        return messages.get("friend.last-seen", "${time}",
                FriendTimeText.format(friend.lastSeen(), System.currentTimeMillis(),
                        ZoneId.systemDefault()));
    }
}

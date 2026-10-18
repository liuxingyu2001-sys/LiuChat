package com.liu.liuchat.service;

import org.bukkit.entity.Player;

/** 好友/邮件界面入口；命令与点击通知只通过这里打开 GUI。 */
public interface SocialMenu {
    void openFriendMain(Player player);

    void openFriendRequests(Player player, int page);

    void openFriendSent(Player player, int page);

    void openAddFriendDialog(Player player);

    void openMailList(Player player);

    void openCompose(Player player);

    void closeAll();
}

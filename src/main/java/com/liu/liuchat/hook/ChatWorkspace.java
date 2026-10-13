package com.liu.liuchat.hook;

import org.bukkit.entity.Player;

import java.util.List;

/**
 * 聊天工作区：把 LiuChat 的状态、屏蔽列表、最近联系人做成可交互界面。
 * <p>
 * 这是<b>安全类型</b>——只用 Bukkit，不含 ArcMenu 任何类，因此命令层可以直接持有它；
 * 具体界面实现由 {@link ArcMenuBridge} 在确认 ArcMenu 可用后才加载。
 */
public interface ChatWorkspace {

    /**
     * 打开工作区。
     *
     * @return true = 已交给界面插件；false = 打开失败（调用方应提示）
     */
    boolean open(Player player, List<String> arguments);

    /** 注销应用并释放资源（插件停用时调用）。 */
    void close();

    /** 界面需要的数据来源；全部由 LiuChat 自己的服务提供，界面插件不碰业务数据。 */
    interface Deps {
        /** 聊天昵称（未设置时为原名）。 */
        String nickname(Player player);

        /** 禁言状态文本，未被禁言返回空串。 */
        String muteText(Player player);

        /** 剩余发言冷却秒数，0 = 可发言。 */
        int cooldown(Player player);

        /** 喇叭余额，-1 = 存储不可用。 */
        int hornBalance(Player player);

        /** 屏蔽的玩家名列表。 */
        List<String> ignored(Player player);

        /** 取消屏蔽；true = 本次确实移除了一个屏蔽项。 */
        boolean unignore(Player player, String name);

        /** 最近一次成功私聊的对象，没有则返回 null。 */
        String recentContact(Player player);
    }
}

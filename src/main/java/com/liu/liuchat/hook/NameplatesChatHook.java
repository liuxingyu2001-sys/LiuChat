package com.liu.liuchat.hook;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * CustomNameplates 聊天气泡门面。
 *
 * <p>本类不引用任何 CustomNameplates 类型；未安装时直接使用引用它的类可能触发
 * {@link NoClassDefFoundError}（软依赖类隔离）。实现在 {@link NameplatesChatResolver}。
 */
public final class NameplatesChatHook {
    private NameplatesChatHook() { }

    /** 把已接受的公屏聊天推送给 CustomNameplates 配置的气泡监听器。 */
    public static void publish(Player sender, String message) {
        if (!Bukkit.getPluginManager().isPluginEnabled("CustomNameplates")) return;
        NameplatesChatResolver.publish(sender, message);
    }
}

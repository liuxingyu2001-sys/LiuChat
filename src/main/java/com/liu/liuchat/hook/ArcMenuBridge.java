package com.liu.liuchat.hook;

import com.liu.liuchat.util.Schedulers;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * ArcMenu 软依赖桥：条件判断与延迟加载。
 * <p>
 * 与 CraftEngine / CustomNameplates 的做法一致：<b>本类不出现任何 ArcMenu 类型</b>，
 * 真正引用 API 的实现类（{@link ArcMenuWorkspace}、{@link ChatWorkspaceSession}）
 * 只在 {@link #available()} 通过后、由 {@link #register} 的方法体首次加载。
 * 这样未安装 ArcMenu 时不会触发 {@code NoClassDefFoundError}。
 * <p>
 * 三个前置条件：<b>ArcMenu</b>、<b>ProtocolLib</b>（ArcMenu 强依赖）都已安装，
 * 且运行环境不是 Folia —— ArcMenu 使用 Bukkit 调度器与摄像机控制，
 * 官方规划书明确「暂不承诺 Folia」，所以在 Folia 上直接不注册，
 * {@code /liuc menu} 会退回 DialogMenu 或提示不可用。
 */
public final class ArcMenuBridge {

    private ArcMenuBridge() { }

    /** ArcMenu 工作区当前是否可用（安装条件 + 运行环境）。 */
    public static boolean available() {
        if (Schedulers.isFolia()) return false;
        if (plugin("ArcMenu") == null || plugin("ProtocolLib") == null) return false;
        return classPresent("com.fentai.arcmenu.api.ArcMenuApi")
                && classPresent("com.fentai.arcmenu.api.ArcMenuApplicationSession");
    }

    /**
     * 注册聊天工作区应用。只在 {@link #available()} 为 true 时调用；
     * 实现类在此处才被类加载。
     *
     * @throws IllegalStateException API 服务未注册（ArcMenu 未完成启用）
     */
    public static ChatWorkspace register(JavaPlugin owner, ChatWorkspace.Deps deps) {
        return new ArcMenuWorkspace(owner, deps);
    }

    private static Plugin plugin(String name) {
        return Bukkit.getPluginManager().getPlugin(name);
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, ArcMenuBridge.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }
}

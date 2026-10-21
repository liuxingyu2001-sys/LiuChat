package com.liu.liuchat.hook;

import com.liu.liuchat.util.Schedulers;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * 可选接入 HuskHomes 4.x：借用它的跨服传送网络，把玩家送到另一个子服的好友身边。
 *
 * <p>只通过反射调用官方 {@code HuskHomesAPI}，本类不引用任何 HuskHomes 类型：
 * 未安装、版本不匹配或 HuskHomes 跨服未开启时安全降级为“只支持同服传送”，
 * 不会影响 LiuChat 加载。</p>
 *
 * <p>调用链等价于 HuskHomes 自身的 {@code /tp <玩家>}：目标在本服时直接传送，
 * 在其它子服时由 HuskHomes 先通过代理把玩家送过去，再完成落地传送。
 * 因此 HuskHomes 需要开启 {@code cross_server.enabled} 并配好代理与 Redis。</p>
 */
public final class HuskHomesHook {
    private static volatile boolean warned;

    private HuskHomesHook() {
    }

    /** HuskHomes 是否已安装并启用（不保证其跨服模式已配置）。 */
    public static boolean available() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("HuskHomes");
        return plugin != null && plugin.isEnabled();
    }

    /**
     * 让 {@code player} 传送到名为 {@code targetName} 的在线玩家身边，可跨子服。
     *
     * <p>必须在玩家所在线程调用（内部会调度到玩家实体线程）。返回的 future 在
     * HuskHomes 接受传送后完成 {@code true}；目标不在线、HuskHomes 拒绝或反射失败
     * 时完成 {@code false}。跨服传送是异步的，返回 {@code true} 只代表已发起。</p>
     */
    public static CompletableFuture<Boolean> teleportToPlayer(JavaPlugin plugin, Player player, String targetName) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Plugin husk = Bukkit.getPluginManager().getPlugin("HuskHomes");
        if (player == null || targetName == null || husk == null || !husk.isEnabled()) {
            result.complete(false);
            return result;
        }
        Schedulers.runFor(plugin, player, () -> {
            try {
                result.complete(HuskHomesResolver.invoke(husk.getClass().getClassLoader(), player, targetName));
            } catch (InvocationTargetException error) {
                warn(plugin, error.getCause() == null ? error : error.getCause());
                result.complete(false);
            } catch (ReflectiveOperationException | LinkageError error) {
                warn(plugin, error);
                result.complete(false);
            }
        });
        return result;
    }

    private static void warn(JavaPlugin plugin, Throwable error) {
        if (warned) return;
        warned = true;
        plugin.getLogger().log(Level.WARNING,
                "HuskHomes 跨服传送调用失败，好友传送暂时只支持同服: " + error);
    }
}

package com.liu.liuchat.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 调度器兼容层：同一套代码在 Paper 与 Folia 上都能正确调度。
 *
 * <p>Folia 移除了 {@code Bukkit.getScheduler()}（调用即抛
 * {@link UnsupportedOperationException}），把世界切成若干独立区域各自 tick。
 * 因此：
 * <ul>
 *   <li>“全服级”逻辑（广播、计时器、异步 IO）走全局区域调度器；
 *   <li>触碰玩家/实体的逻辑必须投递到该实体所在的区域线程（实体调度器）。
 * </ul>
 *
 * <p>本类刻意不引入 FoliaLib 等第三方库：按需探测运行环境，两条路径各自直连
 * Bukkit / Paper 的调度接口。
 *
 * <p>约定：所有 {@code run*} 方法都只在调度器上排队，不保证立即执行；
 * {@link #runFor(Plugin, Entity, Runnable)} 若已在目标实体的区域线程上则立即执行，
 * 否则排到下一次 tick（与 Folia {@code EntityScheduler#execute} 语义一致）。
 */
public final class Schedulers {

    /** 可取消句柄：抹平 BukkitTask 与 Folia ScheduledTask 的差异。 */
    public interface Handle {
        void cancel();

        boolean isCancelled();
    }

    private static final Handle NOOP = new Handle() {
        @Override public void cancel() { }

        @Override public boolean isCancelled() { return true; }
    };

    /** 运行环境是否 Folia（决定是否走区域化调度器）。 */
    private static final boolean FOLIA = detectFolia();

    private Schedulers() { }

    private static boolean detectFolia() {
        try {
            // 仅 Folia 及其分支存在该类；Paper 没有。
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    public static boolean isFolia() {
        return FOLIA;
    }

    /** 当前线程是否在全局（主）线程上：Paper 是主线程，Folia 是全局区域 tick 线程。 */
    public static boolean isGlobalThread() {
        return FOLIA ? Bukkit.getServer().isGlobalTickThread() : Bukkit.isPrimaryThread();
    }

    /**
     * 保证 action 在全局线程上执行：已在全局线程则立即执行，否则排到全局区域。
     * Paper 上等价于“主线程立即执行 / 排到主线程”。
     */
    public static void runGlobal(Plugin plugin, Runnable action) {
        if (isGlobalThread()) {
            action.run();
            return;
        }
        run(plugin, action);
    }

    // ---------------- 全局 / 主线程 ----------------

    /** 下一次全局 tick 执行（Folia：全局区域调度器）。 */
    public static Handle run(Plugin plugin, Runnable action) {
        if (FOLIA) {
            return wrap(Bukkit.getGlobalRegionScheduler().run(plugin, task -> action.run()));
        }
        return wrap(Bukkit.getScheduler().runTask(plugin, action));
    }

    /** 延迟若干 tick 后在全局线程执行。 */
    public static Handle runLater(Plugin plugin, Runnable action, long delayTicks) {
        long delay = Math.max(1L, delayTicks);
        if (FOLIA) {
            return wrap(Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> action.run(), delay));
        }
        return wrap(Bukkit.getScheduler().runTaskLater(plugin, action, delayTicks));
    }

    /** 周期性全局任务，单位 tick。 */
    public static Handle runTimer(Plugin plugin, Runnable action, long delayTicks, long periodTicks) {
        if (FOLIA) {
            return wrap(Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> action.run(),
                    Math.max(1L, delayTicks), Math.max(1L, periodTicks)));
        }
        return wrap(Bukkit.getScheduler().runTaskTimer(plugin, action, delayTicks, periodTicks));
    }

    // ---------------- 异步 ----------------

    /** 异步执行（IO / 网络用）。 */
    public static Handle runAsync(Plugin plugin, Runnable action) {
        if (FOLIA) {
            return wrap(Bukkit.getAsyncScheduler().runNow(plugin, task -> action.run()));
        }
        return wrap(Bukkit.getScheduler().runTaskAsynchronously(plugin, action));
    }

    /** 周期性异步任务，单位 tick。 */
    public static Handle runAsyncTimer(Plugin plugin, Runnable action, long delayTicks, long periodTicks) {
        if (FOLIA) {
            return wrap(Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> action.run(),
                    Math.max(1L, delayTicks) * 50L, Math.max(1L, periodTicks) * 50L, TimeUnit.MILLISECONDS));
        }
        return wrap(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, action, delayTicks, periodTicks));
    }

    // ---------------- 实体 / 玩家 ----------------

    /**
     * 在实体的所属区域线程上执行 action；已有实体区域时立即执行，否则排到下一次 tick。
     * Paper 上等价于“主线程立即执行（已在主线程）/ 排到主线程”。
     */
    public static void runFor(Plugin plugin, Entity entity, Runnable action) {
        if (entity == null) {
            run(plugin, action);
            return;
        }
        if (FOLIA) {
            // execute 在已是该实体区域线程时立即执行；实体已卸载（retired）时静默丢弃。
            entity.getScheduler().execute(plugin, action, () -> { }, 0L);
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    /**
     * 遍历在线玩家，把 action 投递到每个人自己的区域线程。
     * Paper 上就是逐玩家立即执行（调用方需在主线程）。
     */
    public static void forEachPlayer(Plugin plugin, Consumer<Player> action) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            runFor(plugin, player, () -> action.accept(player));
        }
    }

    // ---------------- 句柄包装 ----------------

    private static Handle wrap(org.bukkit.scheduler.BukkitTask task) {
        return new Handle() {
            @Override public void cancel() { task.cancel(); }

            @Override public boolean isCancelled() { return task.isCancelled(); }
        };
    }

    private static Handle wrap(io.papermc.paper.threadedregions.scheduler.ScheduledTask task) {
        return new Handle() {
            @Override public void cancel() { task.cancel(); }

            @Override public boolean isCancelled() { return task.isCancelled(); }
        };
    }

    /** 一个什么都不做的句柄（用于立即执行、无任务可取消的场景）。 */
    public static Handle noop() {
        return NOOP;
    }
}

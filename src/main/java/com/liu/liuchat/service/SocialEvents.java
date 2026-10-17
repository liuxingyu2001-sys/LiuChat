package com.liu.liuchat.service;

import com.liu.liuchat.util.Schedulers;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 把社交事件统一投递到全局线程后触发，保证监听者看到的 Bukkit 状态一致。
 * 已在全局线程时同步触发；异步 API 调用会等待主线程处理完（最多 10 秒）。
 */
public final class SocialEvents {

    private SocialEvents() {
    }

    public static void fire(JavaPlugin plugin, Event event) {
        if (plugin == null || event == null) return;
        if (Schedulers.isGlobalThread()) {
            fireSafely(plugin, event);
            return;
        }
        if (!plugin.isEnabled()) return;
        CompletableFuture<Void> done = new CompletableFuture<>();
        Schedulers.runGlobal(plugin, () -> {
            try {
                if (plugin.isEnabled()) fireSafely(plugin, event);
            } finally {
                done.complete(null);
            }
        });
        try {
            done.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // 主线程关闭/超时：事件不阻塞数据操作，调用方仍能看到结果
        }
    }

    private static void fireSafely(JavaPlugin plugin, Event event) {
        try {
            Bukkit.getPluginManager().callEvent(event);
        } catch (RuntimeException error) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "社交事件监听器执行失败: " + event.getEventName(), error);
        }
    }
}

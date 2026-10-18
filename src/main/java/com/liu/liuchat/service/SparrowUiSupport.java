package com.liu.liuchat.service;

import net.momirealms.sparrow.ui.SparrowUI;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/** Sparrow UI 初始化；失败时保留原版界面作为回退。 */
public final class SparrowUiSupport {
    private static volatile boolean ready;

    private SparrowUiSupport() {
    }

    public static boolean init(JavaPlugin plugin) {
        try {
            SparrowUI.getInstance().setUp(plugin);
            ready = true;
            plugin.getLogger().info("Sparrow UI 已初始化");
        } catch (Throwable error) {
            ready = false;
            plugin.getLogger().log(Level.SEVERE, "Sparrow UI 初始化失败，好友/邮件回退原版界面", error);
        }
        return ready;
    }

    public static boolean ready() {
        return ready;
    }
}

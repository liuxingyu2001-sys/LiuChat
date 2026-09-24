package com.liu.liuchat.hook;

import net.momirealms.customnameplates.api.CustomNameplates;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Publishes accepted public chat to CustomNameplates' configured bubble listeners. */
public final class NameplatesChatHook {
    private NameplatesChatHook() { }

    public static void publish(Player sender, String message) {
        if (!Bukkit.getPluginManager().isPluginEnabled("CustomNameplates")) return;
        try {
            CustomNameplates plugin = CustomNameplates.getInstance();
            if (plugin == null) return;
            var player = plugin.getPlayer(sender.getUniqueId());
            if (player != null && plugin.getChatManager() != null)
                plugin.getChatManager().onChat(player, message, "Global");
        } catch (LinkageError | RuntimeException ex) {
            Bukkit.getLogger().warning("LiuChat: CustomNameplates 气泡推送失败: " + ex);
        }
    }
}

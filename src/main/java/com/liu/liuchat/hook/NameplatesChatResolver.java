package com.liu.liuchat.hook;

import net.momirealms.customnameplates.api.CustomNameplates;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * CustomNameplates 聊天气泡的实际实现（类隔离），仅在插件已启用时加载。
 */
final class NameplatesChatResolver {
    private NameplatesChatResolver() { }

    static void publish(Player sender, String message) {
        try {
            CustomNameplates plugin = CustomNameplates.getInstance();
            if (plugin == null) return;
            var player = plugin.getPlayer(sender.getUniqueId());
            if (player != null && plugin.getChatManager() != null)
                plugin.getChatManager().onChat(player, CraftEngineEmojiHook.resolveBubble(sender, message), "Global");
        } catch (LinkageError | RuntimeException ex) {
            Bukkit.getLogger().warning("LiuChat: CustomNameplates 气泡推送失败: " + ex);
        }
    }
}

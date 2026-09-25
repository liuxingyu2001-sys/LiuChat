package com.liu.liuchat.service;

import com.liu.liuchat.config.ConfigManager;
import com.liu.liuchat.hook.NameplatesChatHook;
import com.liu.liuchat.hook.PapiHook;
import com.liu.liuchat.util.TextUtil;
import net.md_5.bungee.api.chat.BaseComponent;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.plugin.java.JavaPlugin;
import java.time.Duration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Local and remote chat delivery; interactive content is rendered on the receiving server. */
public final class ChatService {
    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final ChatPresentation presentation;
    private final ItemShowcase items;
    private IgnoreService ignores;
    private PlayerProfileService profiles;
    private ChatLogService logs;
    private PublicChatAiService publicAi;

    public void setPlayerProfileService(PlayerProfileService profiles) { this.profiles = profiles; }

    public void setChatLogService(ChatLogService logs) { this.logs = logs; }

    public ChatService(JavaPlugin plugin, ConfigManager config, ChatPresentation presentation, ItemShowcase items) {
        this.plugin = plugin;
        this.config = config;
        this.presentation = presentation;
        this.items = items;
    }

    public void setIgnoreService(IgnoreService ignores) { this.ignores = ignores; }

    public void setPublicChatAi(PublicChatAiService ai) { this.publicAi = ai; }

    public record Dispatch(String message, String itemData, String placeholders, String nick) { }

    /** [i] 物品展示消息：每次展示的物品都可能不同，重复检测要整条跳过。 */
    public boolean isItemShow(String message) {
        return message != null && presentation.itemEnabled()
                && message.contains(presentation.itemToken());
    }

    public void sendOwnChat(Player player, String message) {
        var profile = profiles == null ? null : profiles.get(player);
        String nick = profile == null || profile.nick().isBlank() ? player.getName() : profile.nick();
        String placeholders = presentation.snapshotPlaceholders(player, message);
        if (profile != null && !profile.color().isBlank())
            message = ProfileChatColor.apply(profile.color(), message, presentation.itemToken());
        BaseComponent[] line = presentation.render(config.server(), player.getName(),
                player.getUniqueId().toString(), player.getWorld().getName(), player, message,
                null, player, placeholders, nick);
        player.sendMessage(PaperChatComponents.convert(line, items));
    }
    public Dispatch broadcast(Player player, String message) {
        var profile = profiles == null ? null : profiles.get(player);
        String nick = profile == null || profile.nick().isBlank() ? player.getName() : profile.nick();
        String placeholders = presentation.snapshotPlaceholders(player, message);
        if (profile != null && !profile.color().isBlank()) {
            message = ProfileChatColor.apply(profile.color(), message, presentation.itemToken());
        }
        String itemData = presentation.itemEnabled() && message.contains(presentation.itemToken())
                ? items.snapshot(player) : "";
        if (presentation.itemEnabled() && message.contains(presentation.itemToken()) && itemData.isEmpty()) {
            player.sendMessage("§c手上没有可展示的物品，或物品数据超出跨服消息限制。");
            message = message.replace(presentation.itemToken(), "§7[物品不可展示]§r");
        }
        NameplatesChatHook.publish(player, message);
        deliver(config.server(), player.getUniqueId().toString(), player.getName(),
                player.getWorld().getName(), player, message, itemData, placeholders, nick);
        return new Dispatch(message, itemData, placeholders, nick);
    }

    /** Deliver a preformatted announcement without applying player chat templates or ignore rules. */
    public void broadcastAnnouncement(BaseComponent... components) {
        for (Player online : Bukkit.getOnlinePlayers()) online.spigot().sendMessage(components);
        Bukkit.getConsoleSender().sendMessage(BaseComponent.toLegacyText(components));
    }

    public void broadcastRemote(String originServer, String uuid, String playerName,
                                String message, String itemData, String placeholders, String nick) {
        AiChatSnapshot.Appearance appearance = AiChatSnapshot.decode(placeholders, playerName, uuid);
        if (appearance == null && publicAi != null && publicAi.isAiSender(uuid, playerName)) {
            appearance = new AiChatSnapshot.Appearance(config.aiChatFormat(), publicAi.aiHeadUuid());
        }
        if (appearance != null) {
            broadcastPlain(uuid, playerName,
                    PublicChatAiService.formatComponents(appearance.format(), playerName, message,
                            appearance.headUuid()),
                    PublicChatAiService.formatLine(appearance.format(), playerName, message),
                    message);
        } else {
            deliver(originServer, uuid, playerName, "-", null, message, itemData, placeholders, nick);
        }
        if (publicAi != null) publicAi.onRemoteMessage(uuid, playerName, message);
    }

    /**
     * 固定格式广播（公屏 AI 等）：发送成品聊天组件，不解析聊天格式节点/变量；
     * 仍走忽略列表与聊天日志。consoleLine 为纯文本（无头像）。
     */
    public void broadcastPlain(String uuid, String playerName, BaseComponent[] line,
                               String consoleLine, String rawMessage) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (ignores != null && ignores.ignores(online, uuid, playerName)) continue;
            online.sendMessage(PaperChatComponents.convert(line, items));
        }
        Bukkit.getConsoleSender().sendMessage(consoleLine);
        if (logs != null) logs.record("CHAT", config.server(), playerName, "*", rawMessage);
    }

    private void deliver(String server, String uuid, String playerName, String world,
                         Player sender, String message, String itemData, String placeholders, String nick) {
        String itemId = presentation.itemEnabled() && message.contains(presentation.itemToken())
                ? items.register(playerName, uuid, itemData) : null;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (ignores != null && ignores.ignores(online, uuid, playerName)) continue;
            BaseComponent[] line = presentation.render(server, playerName, uuid, world,
                    sender, message, itemId, online, placeholders, nick);
            online.sendMessage(PaperChatComponents.convert(line, items));
        }
        Bukkit.getConsoleSender().sendMessage(build(config.consoleFormat(), server, playerName, world, sender, message));
        if (logs != null) logs.record("CHAT", server, playerName, "*", message);
    }

    public void horn(Player sender, String message) {
        hornRemote(config.server(), sender.getUniqueId().toString(), sender.getName(), message);
    }

    public void hornRemote(String server, String uuid, String name, String message) {
        String line = TextUtil.color(config.hornFormat().replace("${server}", server).replace("${player}", name))
                .replace("${message}", message);
        Component body = LegacyComponentSerializer.legacySection().deserialize(line);
        Component title = LegacyComponentSerializer.legacySection().deserialize(TextUtil.color(config.hornTitle()));
        java.util.List<String> modes = config.hornModes();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (ignores != null && ignores.ignores(player, uuid, name)) continue;
            if (modes.contains("chat")) player.sendMessage(body);
            if (modes.contains("title")) player.showTitle(Title.title(title, body,
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(config.hornDurationTicks() * 50L),
                            Duration.ofMillis(500))));
            if (modes.contains("actionbar")) player.sendActionBar(body);
            if (modes.contains("bossbar")) {
                BossBar bar = BossBar.bossBar(body, 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
                player.showBossBar(bar);
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> player.hideBossBar(bar),
                        config.hornDurationTicks());
            }
            if (!config.hornSound().isBlank()) {
                try { player.playSound(player.getLocation(), Sound.valueOf(config.hornSound()), 1f, 1f); }
                catch (IllegalArgumentException ignored) { }
            }
        }
        Bukkit.getConsoleSender().sendMessage(line);
        if (logs != null) logs.record("HORN", server, name, "*", message);
    }

    private String build(String template, String server, String playerName, String world,
                         Player papiContext, String message) {
        String line = template.replace("${server}", server).replace("${player}", playerName)
                .replace("${world}", world);
        if (papiContext != null) line = PapiHook.setPlaceholders(papiContext, line);
        return TextUtil.color(line).replace("${message}", message);
    }
}

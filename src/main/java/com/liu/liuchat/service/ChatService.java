package com.liu.liuchat.service;

import com.liu.liuchat.config.ConfigManager;
import com.liu.liuchat.hook.NameplatesChatHook;
import com.liu.liuchat.hook.PapiHook;
import com.liu.liuchat.util.Schedulers;
import com.liu.liuchat.util.TextUtil;
import com.liu.liuchat.util.TimePlaceholders;
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
    private BossBar hornBossBar;
    private Schedulers.Handle hornBossBarTask;

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

    /** 物品展示消息：每次展示的物品都可能不同，重复检测要整条跳过。 */
    public boolean isItemShow(String message) {
        return presentation.hasItemToken(message);
    }

    public void sendOwnChat(Player player, String message) {
        var profile = profiles == null ? null : profiles.get(player);
        String nick = profile == null || profile.nick().isBlank() ? player.getName() : profile.nick();
        String placeholders = presentation.snapshotPlaceholders(player, message);
        if (profile != null && !profile.color().isBlank())
            message = ProfileChatColor.apply(profile.color(), message, presentation.itemTokens());
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
            message = ProfileChatColor.apply(profile.color(), message, presentation.itemTokens());
        }
        String itemData = "";
        if (presentation.hasItemToken(message)) {
            ItemShowcase.Encoded encoded = items.encode(player, presentation.parseItems(message),
                    presentation.itemMaxCount());
            itemData = encoded.data();
            if (itemData.isEmpty()) message = presentation.itemUnavailable(message);
        }
        NameplatesChatHook.publish(player, message);
        deliver(config.server(), player.getUniqueId().toString(), player.getName(),
                player.getWorld().getName(), player, message, itemData, placeholders, nick);
        return new Dispatch(message, itemData, placeholders, nick);
    }

    /** Deliver a preformatted announcement without applying player chat templates or ignore rules. */
    public void broadcastAnnouncement(BaseComponent... components) {
        Schedulers.forEachPlayer(plugin, online -> online.spigot().sendMessage(components));
        Bukkit.getConsoleSender().sendMessage(BaseComponent.toLegacyText(components));
    }

    /** Render the item snapshot on this server so its hover contains native item components. */
    public void broadcastItemAnnouncement(String server, String owner, String uuid, String template, String snapshot) {
        String id = items.register(owner, uuid, snapshot);
        if (id == null) return;
        org.bukkit.inventory.ItemStack stack = items.item(id);
        if (stack == null) return;
        String name = items.name(id, 64);
        String[] parts = template.split("%item%", -1);
        Component line = Component.empty();
        for (int i = 0; i < parts.length; i++) {
            line = line.append(LegacyComponentSerializer.legacySection().deserialize(TextUtil.color(parts[i])));
            if (i < parts.length - 1) {
                line = line.append(LegacyComponentSerializer.legacySection().deserialize("§e[ " + name + " §e]")
                        .hoverEvent(stack.asHoverEvent(event -> event))
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/liuc item " + id)));
            }
        }
        final Component finalLine = line;
        Schedulers.forEachPlayer(plugin, online -> online.sendMessage(finalLine));
        Bukkit.getConsoleSender().sendMessage(TextUtil.color(template.replace("%item%", name)));
    }

    public void broadcastRemote(String originServer, String uuid, String playerName,
                                String message, String itemData, String placeholders, String nick) {
        AiChatSnapshot.Appearance appearance = AiChatSnapshot.decode(placeholders, playerName, uuid);
        if (appearance == null && publicAi != null && publicAi.isAiSender(uuid, playerName)) {
            appearance = new AiChatSnapshot.Appearance(config.aiChatFormat(), publicAi.aiHeadUuid());
        }
        if (appearance != null) {
            broadcastAi(uuid, playerName, appearance.format(), message, appearance.headUuid());
        } else {
            deliver(originServer, uuid, playerName, "-", null, message, itemData, placeholders, nick);
        }
        if (publicAi != null) publicAi.onRemoteMessage(uuid, playerName, message);
    }

    /**
     * 固定格式广播（公屏 AI 等）：发送成品聊天组件，不解析聊天格式节点/变量；
     * 仍走忽略列表与聊天日志。consoleLine 为纯文本（无头像）。
     */
    public void broadcastAi(String uuid, String playerName, String format, String message,
                            java.util.UUID headUuid) {
        broadcastPlain(uuid, playerName,
                PublicChatAiService.formatComponents(format, playerName, message, headUuid,
                        text -> presentation.markMentions(text).text()),
                PublicChatAiService.formatLine(format, playerName, message), message);
    }

    public void broadcastPlain(String uuid, String playerName, BaseComponent[] line,
                               String consoleLine, String rawMessage) {
        // 组件对所有接收者相同，转换一次即可复用
        net.kyori.adventure.text.Component rendered = PaperChatComponents.convert(line, items);
        Schedulers.forEachPlayer(plugin, online -> {
            if (ignores != null && ignores.ignores(online, uuid, playerName)) return;
            online.sendMessage(rendered);
        });
        Bukkit.getConsoleSender().sendMessage(consoleLine);
        if (logs != null) logs.record("CHAT", config.server(), playerName, "*", rawMessage);
    }

    private void deliver(String server, String uuid, String playerName, String world,
                         Player sender, String message, String itemData, String placeholders, String nick) {
        String itemId = presentation.hasItemToken(message)
                ? items.register(playerName, uuid, itemData) : null;
        // 同一条消息对所有接收者的渲染结果相同（viewer 只影响 @ 提示音），
        // 渲染 + Adventure 转换只做一次，逐玩家复用；投递与提示音按玩家所在区域分别执行。
        BaseComponent[] line = presentation.render(server, playerName, uuid, world,
                sender, message, itemId, null, placeholders, nick);
        net.kyori.adventure.text.Component rendered = PaperChatComponents.convert(line, items);
        com.liu.liuchat.util.Mentions.Marked marked = presentation.markMentions(message);
        Schedulers.forEachPlayer(plugin, online -> {
            if (ignores != null && ignores.ignores(online, uuid, playerName)) return;
            online.sendMessage(rendered);
            // 提示音只发给被 @ 的玩家，自己 @ 自己不响（与逐玩家渲染时的条件一致）
            if ((sender == null || !sender.getUniqueId().equals(online.getUniqueId()))
                    && marked.mentions(online.getName())) {
                presentation.playMentionSound(online);
            }
        });
        Bukkit.getConsoleSender().sendMessage(build(config.consoleFormat(), server, playerName, world, sender, message));
        if (logs != null) logs.record("CHAT", server, playerName, "*", message);
    }

    public void horn(Player sender, String message) {
        hornRemote(config.server(), sender.getUniqueId().toString(), sender.getName(), message);
    }

    public void hornRemote(String server, String uuid, String name, String message) {
        Component body = LegacyComponentSerializer.legacySection().deserialize(
                formatHorn(config.hornFormat(), server, name, message));
        Component titleMessage = LegacyComponentSerializer.legacySection().deserialize(
                formatHorn(config.hornTitleMessageFormat(), server, name, message));
        Component actionbarMessage = LegacyComponentSerializer.legacySection().deserialize(
                formatHorn(config.hornActionbarMessageFormat(), server, name, message));
        Component title = LegacyComponentSerializer.legacySection().deserialize(TextUtil.color(config.hornTitle()));
        Title.Times titleTimes = Title.Times.times(Duration.ofMillis(300),
                Duration.ofMillis(config.hornDurationTicks() * 50L), Duration.ofMillis(500));
        java.util.List<String> modes = config.hornModes();
        BossBar bar = null;
        if (modes.contains("bossbar")) {
            if (hornBossBar != null) {
                BossBar previous = hornBossBar;
                Schedulers.forEachPlayer(plugin, viewer -> viewer.hideBossBar(previous));
            }
            if (hornBossBarTask != null) hornBossBarTask.cancel();
            hornBossBar = BossBar.bossBar(body, 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
            bar = hornBossBar;
        }
        Sound sound = null;
        if (!config.hornSound().isBlank()) {
            try { sound = Sound.valueOf(config.hornSound()); }
            catch (IllegalArgumentException ignored) { }
        }
        final Sound hornSound = sound;
        final BossBar hornBar = bar;
        Schedulers.forEachPlayer(plugin, player -> {
            if (ignores != null && ignores.ignores(player, uuid, name)) return;
            if (modes.contains("chat")) player.sendMessage(body);
            if (modes.contains("title")) player.showTitle(Title.title(title, titleMessage, titleTimes));
            if (modes.contains("actionbar")) player.sendActionBar(actionbarMessage);
            if (hornBar != null) player.showBossBar(hornBar);
            if (hornSound != null) player.playSound(player.getLocation(), hornSound, 1f, 1f);
        });
        if (bar != null) {
            BossBar scheduledBar = bar;
            hornBossBarTask = Schedulers.runLater(plugin, () -> {
                Schedulers.forEachPlayer(plugin, viewer -> viewer.hideBossBar(scheduledBar));
                if (hornBossBar == scheduledBar) hornBossBar = null;
                hornBossBarTask = null;
            }, config.hornDurationTicks());
        }
        String line = TextUtil.color(formatHorn(config.hornFormat(), server, name, message));
        Bukkit.getConsoleSender().sendMessage(line);
        if (logs != null) logs.record("HORN", server, name, "*", message);
    }

    static String formatHorn(String template, String server, String player, String message) {
        return TextUtil.color(TimePlaceholders.apply(template, java.time.ZonedDateTime.now())
                .replace("${server}", server).replace("${player}", player))
                .replace("${message}", message);
    }

    public void closeHornDisplay() {
        if (hornBossBarTask != null) hornBossBarTask.cancel();
        hornBossBarTask = null;
        if (hornBossBar != null) {
            BossBar bar = hornBossBar;
            Schedulers.forEachPlayer(plugin, player -> player.hideBossBar(bar));
            hornBossBar = null;
        }
    }

    private String build(String template, String server, String playerName, String world,
                         Player papiContext, String message) {
        String line = template.replace("${server}", server).replace("${player}", playerName)
                .replace("${world}", world);
        if (papiContext != null) line = PapiHook.setPlaceholders(papiContext, line);
        return TextUtil.color(line).replace("${message}", message);
    }
}

package com.liu.liuchat.hook;

import com.liu.liuchat.config.ConfigManager;
import com.liu.liuchat.listener.ChatListener;
import com.liu.liuchat.model.MuteData;
import com.liu.liuchat.service.IgnoreService;
import com.liu.liuchat.service.MuteService;
import com.liu.liuchat.service.PlayerProfileService;
import com.liu.liuchat.service.TellService;
import com.liu.liuchat.storage.Database;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * %liuchat_xxx% 变量：
 * <ul>
 *   <li>%liuchat_server% —— 子服标识</li>
 *   <li>%liuchat_world% —— 玩家所在世界</li>
 *   <li>%liuchat_nick% —— 聊天昵称（未设置时为原名）</li>
 *   <li>%liuchat_nick_raw% —— 已设置的聊天昵称（未设置时为空）</li>
 *   <li>%liuchat_muted% —— 是否被禁言 true/false</li>
 *   <li>%liuchat_muted_time% —— 禁言剩余时长（永久/空）</li>
 *   <li>%liuchat_muted_reason% —— 禁言原因（未禁言为空）</li>
 *   <li>%liuchat_ignore_count% —— 屏蔽的玩家数</li>
 *   <li>%liuchat_recent_contact% —— 最近一次成功私聊的对象（没有则 -）</li>
 *   <li>%liuchat_horn_balance% —— 喇叭余额（读库带 5 秒缓存，不可用为 -）</li>
 *   <li>%liuchat_cooldown% —— 剩余发言冷却秒数（0 = 可发言）</li>
 *   <li>%liuchat_ai% —— AI 助手是否可用 true/false</li>
 * </ul>
 *
 * <p>新变量只读内存状态；唯一的读库项 {@code horn_balance} 带短期缓存，
 * 避免出现在聊天格式里时每条消息都打数据库。空值返回 "-"，
 * 因为 DialogMenu 等面板把空字符串当成「未接入」。
 */
public final class LiuChatExpansion extends PlaceholderExpansion {

    private final ConfigManager config;
    private final MuteService muteService;
    private final PlayerProfileService profiles;
    private final IgnoreService ignores;
    private final TellService tellService;
    private final Database database;
    private final ChatListener chatListener;
    private final String version;
    /** 喇叭余额缓存：uuid -> [到期毫秒, 余额]。 */
    private final Map<String, long[]> hornCache = new ConcurrentHashMap<>();
    private static final long HORN_TTL_MILLIS = 5_000L;

    public LiuChatExpansion(ConfigManager config, MuteService muteService,
                            PlayerProfileService profiles, IgnoreService ignores,
                            TellService tellService, Database database,
                            ChatListener chatListener, JavaPlugin plugin) {
        this.config = config;
        this.muteService = muteService;
        this.profiles = profiles;
        this.ignores = ignores;
        this.tellService = tellService;
        this.database = database;
        this.chatListener = chatListener;
        this.version = plugin.getDescription().getVersion();
    }

    @Override
    public String getIdentifier() {
        return "liuchat";
    }

    @Override
    public String getAuthor() {
        return "liuxingyu2001";
    }

    @Override
    public String getVersion() {
        return version;
    }

    /** 插件重载后不把本扩展注销掉 */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        if (player == null || params == null) {
            return null;
        }
        String key = params.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "server" -> config.server();
            case "world" -> player.getWorld().getName();
            case "nick" -> nick(profiles.get(player).nick(), player.getName());
            case "nick_raw" -> profiles.get(player).nick();
            case "muted", "muted_time", "muted_reason" -> mutedText(player, key);
            case "ignore_count" -> ignores == null ? "-"
                    : String.valueOf(ignores.names(player).size());
            case "recent_contact" -> recentContact(player);
            case "horn_balance" -> hornBalance(player);
            case "cooldown" -> chatListener == null ? "0"
                    : String.valueOf(chatListener.cooldownLeft(player, System.currentTimeMillis()));
            case "ai" -> String.valueOf(config.aiAssistantEnabled());
            default -> null;
        };
    }

    static String nick(String stored, String playerName) {
        return stored == null || stored.isBlank() ? playerName : stored;
    }

    /** 最近私聊对象：没有则用 "-"，避免面板把空串显示成「未接入」。 */
    private String recentContact(Player player) {
        if (tellService == null) return "-";
        String contact = tellService.recentContact(player.getUniqueId());
        return contact == null || contact.isBlank() ? "-" : contact;
    }

    /**
     * 喇叭余额：{@link Database#hornBalance} 是同步读（排在此前所有写之后），
     * 占位符可能每条聊天都解析，所以按玩家缓存 5 秒；数据库不可用返回 "-"。
     */
    private String hornBalance(Player player) {
        if (database == null) return "-";
        String uuid = player.getUniqueId().toString();
        long now = System.currentTimeMillis();
        long[] cached = hornCache.get(uuid);
        if (cached != null && cached[0] > now) {
            return balanceText((int) cached[1]);
        }
        int balance = database.hornBalance(uuid);
        hornCache.put(uuid, new long[]{now + HORN_TTL_MILLIS, balance});
        return balanceText(balance);
    }

    /** -1 表示存储不可用（内存模式或连接失败）。 */
    static String balanceText(int balance) {
        return balance < 0 ? "-" : String.valueOf(balance);
    }

    private String mutedText(Player player, String key) {
        Optional<MuteData> muted =
                muteService.check(player.getUniqueId().toString(), player.getName());
        if (muted.isEmpty()) {
            return "";
        }
        MuteData mute = muted.get();
        return switch (key) {
            case "muted" -> "true";
            case "muted_time" -> mute.isPermanent()
                    ? "永久"
                    : com.liu.liuchat.util.TextUtil.formatDuration(
                    mute.expireAt() - System.currentTimeMillis());
            default -> mute.reason() == null ? "" : mute.reason();
        };
    }
}

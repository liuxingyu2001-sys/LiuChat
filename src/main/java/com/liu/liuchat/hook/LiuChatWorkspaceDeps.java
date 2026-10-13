package com.liu.liuchat.hook;

import com.liu.liuchat.listener.ChatListener;
import com.liu.liuchat.model.MuteData;
import com.liu.liuchat.service.IgnoreService;
import com.liu.liuchat.service.MuteService;
import com.liu.liuchat.service.PlayerProfileService;
import com.liu.liuchat.service.TellService;
import com.liu.liuchat.storage.Database;
import com.liu.liuchat.util.TextUtil;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

/**
 * 聊天工作区的数据来源：直接复用 LiuChat 已有服务，
 * 不新增存储、不改业务规则——界面只是把 {@code /liuc ignorelist}、
 * {@code /liuc unignore}、{@code /r} 背后已有的状态与操作换个入口。
 * 主类在启用时用它装配工作区。
 */
public final class LiuChatWorkspaceDeps implements ChatWorkspace.Deps {

    private final MuteService muteService;
    private final PlayerProfileService profiles;
    private final IgnoreService ignores;
    private final TellService tells;
    private final Database database;
    private final ChatListener chatListener;

    public LiuChatWorkspaceDeps(MuteService muteService,
                         PlayerProfileService profiles, IgnoreService ignores,
                         TellService tells, Database database, ChatListener chatListener) {
        this.muteService = muteService;
        this.profiles = profiles;
        this.ignores = ignores;
        this.tells = tells;
        this.database = database;
        this.chatListener = chatListener;
    }

    @Override
    public String nickname(Player player) {
        String stored = profiles.get(player).nick();
        return stored == null || stored.isBlank() ? player.getName() : stored;
    }

    @Override
    public String muteText(Player player) {
        Optional<MuteData> muted = muteService.check(player.getUniqueId().toString(), player.getName());
        if (muted.isEmpty()) return "";
        MuteData mute = muted.get();
        return mute.isPermanent() ? "永久"
                : TextUtil.formatDuration(mute.expireAt() - System.currentTimeMillis());
    }

    @Override
    public int cooldown(Player player) {
        return chatListener == null ? 0 : chatListener.cooldownLeft(player, System.currentTimeMillis());
    }

    @Override
    public int hornBalance(Player player) {
        return database == null ? -1 : database.hornBalance(player.getUniqueId().toString());
    }

    @Override
    public List<String> ignored(Player player) {
        return ignores == null ? List.of() : ignores.names(player);
    }

    @Override
    public boolean unignore(Player player, String name) {
        return ignores != null && ignores.remove(player, name);
    }

    @Override
    public String recentContact(Player player) {
        if (tells == null) return null;
        String contact = tells.recentContact(player.getUniqueId());
        return contact == null || contact.isBlank() ? null : contact;
    }
}

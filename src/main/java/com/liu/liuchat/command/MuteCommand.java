package com.liu.liuchat.command;

import com.liu.liuchat.config.MessageManager;
import com.liu.liuchat.model.MuteData;
import com.liu.liuchat.service.MuteService;
import com.liu.liuchat.util.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;

/**
 * /lc mute <玩家> <时长|0永久> [原因]
 */
public final class MuteCommand implements ChatCommand {

    private final MessageManager messages;
    private final MuteService muteService;

    public MuteCommand(MessageManager messages, MuteService muteService) {
        this.messages = messages;
        this.muteService = muteService;
    }

    @Override
    public String name() {
        return "mute";
    }

    @Override
    public String permission() {
        return "liuchat.mute";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            messages.send(sender, "mute.usage");
            return;
        }
        long duration = TextUtil.parseDuration(args[1]);
        if (duration < 0) {
            messages.send(sender, "mute.invalid-time", "${input}", args[1]);
            return;
        }
        String targetName = args[0];
        OfflinePlayer target = resolve(targetName);
        long expireAt = duration == 0 ? MuteData.PERMANENT : System.currentTimeMillis() + duration;
        String reason = args.length >= 3
                ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                : messages.getRaw("mute.default-reason");
        MuteData mute = new MuteData(
                target.getUniqueId().toString(),
                target.getName() != null ? target.getName() : targetName,
                expireAt, reason, sender.getName());
        muteService.mute(mute);

        String timeText = messages.muteTimeText(mute);
        messages.send(sender, "mute.success",
                "${player}", mute.name(), "${time}", timeText, "${reason}", reason);
        Player online = Bukkit.getPlayerExact(targetName);
        if (online != null) {
            messages.send(online, "mute.notify", "${time}", timeText, "${reason}", reason);
        }
    }

    /** 在线优先拿真实 uuid；离线按名字解析（可能进用户缓存） */
    private OfflinePlayer resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        return online != null ? online : Bukkit.getOfflinePlayer(name);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return onlinePlayers(args[0]);
        }
        return List.of();
    }
}

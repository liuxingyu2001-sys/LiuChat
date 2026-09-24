package com.liu.liuchat.command;

import com.liu.liuchat.config.MessageManager;
import com.liu.liuchat.model.MuteData;
import com.liu.liuchat.service.CrossServerService;
import com.liu.liuchat.service.MuteService;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * /liuc unmute <玩家>
 */
public final class UnmuteCommand implements ChatCommand {

    private final MessageManager messages;
    private final MuteService muteService;
    private final CrossServerService crossServer;

    public UnmuteCommand(MessageManager messages, MuteService muteService, CrossServerService crossServer) {
        this.messages = messages;
        this.muteService = muteService;
        this.crossServer = crossServer;
    }

    @Override
    public String name() {
        return "unmute";
    }

    @Override
    public String permission() {
        return "liuchat.unmute";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length < 1) {
            messages.send(sender, "unmute.usage");
            return;
        }
        Optional<MuteData> found = muteService.find(args[0]);
        if (found.isEmpty()) {
            messages.send(sender, "unmute.not-muted", "${player}", args[0]);
            return;
        }
        MuteData mute = found.get();
        muteService.unmute(mute);
        crossServer.publishUnmute(mute.uuid());
        messages.send(sender, "unmute.success", "${player}", mute.name());
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return muteService.mutedNames().stream()
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted()
                    .toList();
        }
        return List.of();
    }
}

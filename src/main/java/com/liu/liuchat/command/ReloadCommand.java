package com.liu.liuchat.command;

import com.liu.liuchat.config.ConfigManager;
import com.liu.liuchat.config.MessageManager;
import com.liu.liuchat.service.ChatPresentation;
import com.liu.liuchat.service.AiSkillService;
import com.liu.liuchat.listener.NpcAssistantBridge;
import com.liu.liuchat.command.DialogCommand;
import org.bukkit.command.CommandSender;

/**
 * /lc reload —— 重载配置与语言文件。
 */
public final class ReloadCommand implements ChatCommand {

    private final ConfigManager config;
    private final MessageManager messages;
    private final ChatPresentation presentation;
    private final DialogCommand dialog;
    private final AiSkillService skills;
    private final NpcAssistantBridge npcBridge;

    public ReloadCommand(ConfigManager config, MessageManager messages,
                         ChatPresentation presentation, DialogCommand dialog, AiSkillService skills,
                         NpcAssistantBridge npcBridge) {
        this.config = config;
        this.messages = messages;
        this.presentation = presentation;
        this.dialog = dialog;
        this.skills = skills;
        this.npcBridge = npcBridge;
    }

    @Override
    public String name() {
        return "reload";
    }

    @Override
    public String permission() {
        return "liuchat.reload";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        config.reload();
        messages.reload();
        presentation.reload();
        dialog.reload();
        npcBridge.reload();
        try {
            skills.reload();
        } catch (java.io.IOException e) {
            com.liu.liuchat.LiuChat.instance().getLogger().warning("AI skills 加载失败: " + e.getMessage());
        }
        messages.send(sender, "reload.success");
    }
}

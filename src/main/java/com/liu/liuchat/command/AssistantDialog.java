package com.liu.liuchat.command;

import com.liu.liuchat.config.ConfigManager;
import com.liu.liuchat.config.MessageManager;
import com.liu.liuchat.service.AiAssistantService;
import com.liu.liuchat.service.AiAnswerFormatter;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/** Private question/answer dialogs for a configured assistant or a Citizens NPC. */
public final class AssistantDialog {
    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final MessageManager messages;
    private final AiAssistantService assistant;

    public AssistantDialog(JavaPlugin plugin, ConfigManager config, MessageManager messages,
                           AiAssistantService assistant) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.assistant = assistant;
    }

    public void open(Player player, String name, String title) {
        if (!player.hasPermission("liuchat.ask")) { messages.send(player, "no-permission"); return; }
        if (!config.aiAssistantEnabled()) { messages.send(player, "ai.unavailable"); return; }
        ActionButton send = ActionButton.create(Component.text("发送问题"), Component.empty(), 160,
                DialogAction.customClick((response, audience) -> main(() -> {
                    if (!player.equals(audience) || !player.isOnline()) return;
                    String question = response.getText("question");
                    if (question == null || question.isBlank()) {
                        messages.send(player, "ai.ask-usage");
                        return;
                    }
                    AiAssistantService.Status result = assistant.ask(player, name, question.strip(), reply -> {
                        if (reply.status() == AiAssistantService.Status.OK)
                            showAnswer(player, name, title, reply.answer());
                        else messages.send(player, "ai.failed");
                    });
                    switch (result) {
                        case OK -> messages.send(player, "ai.thinking");
                        case BUSY -> messages.send(player, "ai.pending");
                        case TOO_LONG -> messages.send(player, "ai.too-long");
                        case UNAVAILABLE -> messages.send(player, "ai.unavailable");
                        case UNKNOWN_ASSISTANT -> messages.send(player, "ai.assistant-unknown", "${assistant}", name);
                        case UNKNOWN_SKILL -> messages.send(player, "ai.skill-unknown", "${skill}",
                                config.aiAssistantProfiles().getOrDefault(name, config.aiAssistantDefaultSkill()));
                        default -> messages.send(player, "ai.failed");
                    }
                }), ClickCallback.Options.builder().uses(1).build()));
        ActionButton back = ActionButton.create(Component.text("返回"), Component.empty(), 100,
                DialogAction.customClick((response, audience) -> { },
                        ClickCallback.Options.builder().uses(1).build()));
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text(title))
                        .inputs(List.of(DialogInput.text("question", Component.text("向 " + title + " 提问"))
                                .maxLength(config.aiAssistantMaxQuestion()).build())).build())
                .type(DialogType.multiAction(List.of(send), back, 1)));
        player.showDialog(dialog);
    }

    private void showAnswer(Player player, String name, String title, String answer) {
        if (!player.isOnline()) return;
        String clean = answer.replace('§', '&');
        List<io.papermc.paper.registry.data.dialog.body.DialogBody> body = AiAnswerFormatter.lines(clean).stream()
                .map(line -> (io.papermc.paper.registry.data.dialog.body.DialogBody)
                        DialogBody.plainMessage(Component.text(com.liu.liuchat.util.TextUtil.color(line)), config.aiAssistantDialogWidth()))
                .toList();
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text(title)).body(body).build())
                .type(DialogType.multiAction(List.of(ActionButton.create(Component.text("继续提问"), Component.empty(), 140,
                        DialogAction.customClick((response, audience) -> main(() -> {
                            if (player.equals(audience)) open(player, name, title);
                        }), ClickCallback.Options.builder().uses(1).build()))),
                        ActionButton.create(Component.text("关闭"), Component.empty(), 100,
                                DialogAction.customClick((response, audience) -> { },
                                        ClickCallback.Options.builder().uses(1).build())), 2)));
        player.showDialog(dialog);
    }

    private void main(Runnable action) {
        if (Bukkit.isPrimaryThread()) action.run();
        else Bukkit.getScheduler().runTask(plugin, action);
    }
}

package com.liu.liuchat.command;

import com.liu.liuchat.config.MessageManager;
import com.liu.liuchat.service.PlayerProfileService;
import com.liu.liuchat.util.TextUtil;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Chat color dialog with single-color and two-endpoint gradient modes. */
public final class ColorDialog {
    private static final Pattern HEX = Pattern.compile("(?i)(?:&#|<#)([0-9a-f]{6})");
    private static final Pattern GRADIENT = Pattern.compile("(?i)<gradient:#([0-9a-f]{6}):#([0-9a-f]{6})>");
    private static final Pattern LEGACY = Pattern.compile("(?i)&([0-9a-f])");
    private static final int[][] PRESETS = {
            {255, 255, 255}, {255, 85, 85}, {85, 255, 85}, {85, 170, 255},
            {255, 170, 0}, {255, 85, 255}, {170, 170, 170}, {255, 255, 85}
    };
    private final JavaPlugin plugin;
    private final PlayerProfileService profiles;
    private final MessageManager messages;

    public ColorDialog(JavaPlugin plugin, PlayerProfileService profiles, MessageManager messages) {
        this.plugin = plugin;
        this.profiles = profiles;
        this.messages = messages;
    }

    public void open(Player player) {
        if (!player.hasPermission("liuchat.chatcolor")) { messages.send(player, "no-permission"); return; }
        open(player, fromStored(profiles.get(player).color()));
    }

    private void open(Player player, ColorState state) {
        List<ActionButton> actions = new ArrayList<>();
        for (int[] preset : PRESETS) {
            Component label = Component.text("■").color(TextColor.color(rgb(preset)));
            actions.add(ActionButton.create(label, Component.text("选用固定颜色"), 50,
                    DialogAction.customClick((view, audience) -> main(player, audience, () -> {
                        if (player.isOnline()) open(player, fromView(view, state).withStart(preset).single());
                    }), ClickCallback.Options.builder().uses(1).build())));
        }
        actions.add(ActionButton.create(Component.text("预览"), Component.text("应用滑块值并刷新预览"), 100,
                DialogAction.customClick((view, audience) -> main(player, audience,
                        () -> { if (player.isOnline()) open(player, fromView(view, state)); }),
                        ClickCallback.Options.builder().uses(1).build())));
        actions.add(ActionButton.create(Component.text("保存"), Component.text("保存当前单色或渐变设置"), 100,
                DialogAction.customClick((view, audience) -> main(player, audience, () -> {
                    if (!player.isOnline() || !player.hasPermission("liuchat.chatcolor")) return;
                    profiles.color(player, fromView(view, state).serialize());
                    messages.send(player, "profile.saved");
                }), ClickCallback.Options.builder().uses(1).build())));
        actions.add(ActionButton.create(Component.text("清除格式"), Component.empty(), 100,
                DialogAction.customClick((view, audience) -> main(player, audience, () -> {
                    if (!player.isOnline() || !player.hasPermission("liuchat.chatcolor")) return;
                    profiles.color(player, "");
                    messages.send(player, "profile.saved");
                }), ClickCallback.Options.builder().uses(1).build())));

        List<io.papermc.paper.registry.data.dialog.input.DialogInput> inputs = new ArrayList<>();
        inputs.add(DialogInput.bool("gradient", Component.text("渐变模式（关闭=单色）"), state.gradient, "true", "false"));
        inputs.add(DialogInput.numberRange("red", 250, Component.text("聊天文本起始颜色 · 红 0-255"), "options.generic_value", 0, 255, (float) state.r1, 1f));
        inputs.add(DialogInput.numberRange("green", 250, Component.text("绿 0-255"), "options.generic_value", 0, 255, (float) state.g1, 1f));
        inputs.add(DialogInput.numberRange("blue", 250, Component.text("蓝 0-255"), "options.generic_value", 0, 255, (float) state.b1, 1f));
        inputs.add(DialogInput.numberRange("end_red", 250, Component.text("聊天文本结束颜色 · 红 0-255"), "options.generic_value", 0, 255, (float) state.r2, 1f));
        inputs.add(DialogInput.numberRange("end_green", 250, Component.text("绿 0-255"), "options.generic_value", 0, 255, (float) state.g2, 1f));
        inputs.add(DialogInput.numberRange("end_blue", 250, Component.text("蓝 0-255"), "options.generic_value", 0, 255, (float) state.b2, 1f));
        inputs.add(DialogInput.bool("bold", Component.text("加粗"), state.bold, "true", "false"));
        inputs.add(DialogInput.bool("underline", Component.text("下划线"), state.underline, "true", "false"));

        Component preview = LegacyComponentSerializer.legacySection().deserialize(TextUtil.color(
                state.serialize() + "预览：聊天文字效果"));
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("聊天颜色与渐变"))
                        .body(List.of(
                                DialogBody.plainMessage(Component.text("拖动红、绿、蓝滑块调整颜色；开启渐变后，起始色与结束色决定文字两端颜色。")),
                                DialogBody.plainMessage(preview))).inputs(inputs).build())
                .type(DialogType.multiAction(actions, ActionButton.create(Component.text("关闭"), Component.empty(), 100,
                        DialogAction.customClick((view, audience) -> { }, ClickCallback.Options.builder().uses(1).build())), 8)));
        player.showDialog(dialog);
    }

    private void main(Player player, net.kyori.adventure.audience.Audience audience, Runnable action) {
        if (!player.equals(audience) || !plugin.isEnabled()) return;
        if (Bukkit.isPrimaryThread()) action.run();
        else Bukkit.getScheduler().runTask(plugin, action);
    }

    private static ColorState fromView(DialogResponseView view, ColorState previous) {
        return new ColorState(Boolean.TRUE.equals(view.getBoolean("gradient")),
                channel(view.getFloat("red"), previous.r1), channel(view.getFloat("green"), previous.g1),
                channel(view.getFloat("blue"), previous.b1), channel(view.getFloat("end_red"), previous.r2),
                channel(view.getFloat("end_green"), previous.g2), channel(view.getFloat("end_blue"), previous.b2),
                Boolean.TRUE.equals(view.getBoolean("bold")), Boolean.TRUE.equals(view.getBoolean("underline")));
    }

    private static int channel(Float value, int fallback) {
        return value == null ? fallback : Math.max(0, Math.min(255, Math.round(value)));
    }
    private static int rgb(int[] value) { return rgb(value[0], value[1], value[2]); }
    private static int rgb(int r, int g, int b) { return r << 16 | g << 8 | b; }

    static ColorState fromStored(String stored) {
        Matcher gradient = GRADIENT.matcher(stored == null ? "" : stored);
        if (gradient.find()) return new ColorState(true, hex(gradient.group(1)), hex(gradient.group(2)),
                stored.contains("&l"), stored.contains("&n"));
        Matcher matcher = HEX.matcher(stored == null ? "" : stored);
        int color = 0xffffff;
        if (matcher.find()) color = Integer.parseInt(matcher.group(1), 16);
        else {
            Matcher legacy = LEGACY.matcher(stored == null ? "" : stored);
            if (legacy.find()) {
                var chatColor = net.md_5.bungee.api.ChatColor.getByChar(legacy.group(1).charAt(0));
                if (chatColor != null && chatColor.getColor() != null) color = chatColor.getColor().getRGB() & 0xffffff;
            }
        }
        return new ColorState(false, color >>> 16 & 255, color >>> 8 & 255, color & 255,
                color >>> 16 & 255, color >>> 8 & 255, color & 255,
                stored != null && stored.contains("&l"), stored != null && stored.contains("&n"));
    }

    private static int[] hex(String value) {
        int color = Integer.parseInt(value, 16);
        return new int[]{color >>> 16 & 255, color >>> 8 & 255, color & 255};
    }

    record ColorState(boolean gradient, int r1, int g1, int b1, int r2, int g2, int b2,
                      boolean bold, boolean underline) {
        ColorState(boolean gradient, int[] start, int[] end, boolean bold, boolean underline) {
            this(gradient, start[0], start[1], start[2], end[0], end[1], end[2], bold, underline);
        }
        ColorState(int r, int g, int b, boolean bold, boolean underline) {
            this(false, r, g, b, r, g, b, bold, underline);
        }
        ColorState withStart(int[] value) { return new ColorState(gradient, value[0], value[1], value[2], r2, g2, b2, bold, underline); }
        ColorState withRgb(int[] value) { return withStart(value); }
        ColorState single() { return new ColorState(false, r1, g1, b1, r2, g2, b2, bold, underline); }
        int r() { return r1; }
        int g() { return g1; }
        int b() { return b1; }
        String serialize() {
            String format = gradient ? String.format("<gradient:#%02X%02X%02X:#%02X%02X%02X>", r1, g1, b1, r2, g2, b2)
                    : String.format("&#%02X%02X%02X", r1, g1, b1);
            return format + (bold ? "&l" : "") + (underline ? "&n" : "");
        }
    }
}

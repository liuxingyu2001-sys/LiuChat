package com.liu.liuchat.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.inventory.ItemStack;

/** Paper-native chat delivery preserves complete item data components on hover. */
public final class PaperChatComponents {
    private PaperChatComponents() { }

    public static Component convert(BaseComponent[] parts, ItemShowcase items) {
        Component result = Component.empty();
        for (BaseComponent part : parts) result = result.append(convert(part, items));
        return result;
    }

    private static Component convert(BaseComponent part, ItemShowcase items) {
        Component result;
        if (part.getInsertion() != null && part.getInsertion().startsWith("liuchat-head:")) {
            result = Component.object(ObjectContents.playerHead(
                    java.util.UUID.fromString(part.getInsertion().substring("liuchat-head:".length()))));
        } else if (part.getInsertion() != null && part.getInsertion().startsWith("liuchat-image:")) {
            String mini = new String(java.util.Base64.getDecoder().decode(
                    part.getInsertion().substring("liuchat-image:".length())), java.nio.charset.StandardCharsets.UTF_8);
            result = mini.startsWith("ce-json:")
                    ? GsonComponentSerializer.gson().deserialize(mini.substring("ce-json:".length()))
                    : MiniMessage.miniMessage().deserialize(mini);
        } else if (part instanceof TextComponent text) {
            result = Component.text(text.getText());
        } else {
            result = Component.text(part.toPlainText());
        }
        if (part.getColorRaw() != null && part.getColorRaw().getColor() != null)
            result = result.color(TextColor.color(part.getColorRaw().getColor().getRGB() & 0xffffff));
        if (part.isBoldRaw() != null) result = result.decoration(TextDecoration.BOLD, part.isBoldRaw());
        if (part.isItalicRaw() != null) result = result.decoration(TextDecoration.ITALIC, part.isItalicRaw());
        if (part.isUnderlinedRaw() != null) result = result.decoration(TextDecoration.UNDERLINED, part.isUnderlinedRaw());
        if (part.isStrikethroughRaw() != null) result = result.decoration(TextDecoration.STRIKETHROUGH, part.isStrikethroughRaw());
        if (part.isObfuscatedRaw() != null) result = result.decoration(TextDecoration.OBFUSCATED, part.isObfuscatedRaw());

        net.md_5.bungee.api.chat.ClickEvent click = part.getClickEvent();
        if (click != null) {
            result = switch (click.getAction()) {
                case RUN_COMMAND -> result.clickEvent(ClickEvent.runCommand(click.getValue()));
                case SUGGEST_COMMAND -> result.clickEvent(ClickEvent.suggestCommand(click.getValue()));
                case OPEN_URL -> result.clickEvent(ClickEvent.openUrl(click.getValue()));
                case COPY_TO_CLIPBOARD -> result.clickEvent(ClickEvent.copyToClipboard(click.getValue()));
                default -> result;
            };
        }
        net.md_5.bungee.api.chat.HoverEvent hover = part.getHoverEvent();
        if (hover != null && hover.getAction() == net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_ITEM
                && click != null && click.getValue().startsWith("/liuc item ")) {
            ItemStack stack = items.item(click.getValue().substring("/liuc item ".length()));
            if (stack != null) result = result.hoverEvent(stack.asHoverEvent(event -> event));
        } else if (hover != null && hover.getAction() == net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT
                && result.hoverEvent() == null) {
            result = result.hoverEvent(HoverEvent.showText(convert(hover.getValue(), items)));
        }
        if (part.getExtra() != null) for (BaseComponent child : part.getExtra())
            result = result.append(convert(child, items));
        return result;
    }
}

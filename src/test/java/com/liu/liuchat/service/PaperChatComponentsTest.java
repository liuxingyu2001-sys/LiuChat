package com.liu.liuchat.service;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PaperChatComponentsTest {
    @Test void preservesClickAndHoverActions() {
        TextComponent node = new TextComponent(TextComponent.fromLegacyText("§aAlice"));
        node.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/tell Alice "));
        node.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                TextComponent.fromLegacyText("§7Reply")));
        var result = PaperChatComponents.convert(new TextComponent[]{node}, null).children().get(0);
        assertEquals("/tell Alice ", result.clickEvent().value());
        assertNotNull(result.hoverEvent());
        assertEquals("Alice", PlainTextComponentSerializer.plainText().serialize(result));
    }

    @Test void preservesCustomNameplatesFontAndActions() {
        String mini = "<font:custom_nameplates:default>\uE001</font><red>Alice</red>";
        TextComponent node = new TextComponent("");
        node.setInsertion("liuchat-image:" + java.util.Base64.getEncoder().encodeToString(
                mini.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        node.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpa Alice"));
        var result = PaperChatComponents.convert(new TextComponent[]{node}, null).children().get(0);
        assertEquals("/tpa Alice", result.clickEvent().value());
        assertEquals("\uE001Alice", PlainTextComponentSerializer.plainText().serialize(result));
        assertEquals(net.kyori.adventure.key.Key.key("custom_nameplates:default"), result.children().get(0).font());
    }

    @Test void craftEngineEmojiKeepsItsOwnHoverInsideChatNode() {
        TextComponent line = new TextComponent();
        ChatPresentation.appendEmojis(line, "hi :8ball:!", java.util.Map.of(":8ball:",
                "<hover:show_text:'使用<yellow>\":8ball:\"</yellow>来发送表情\"⑧\"'><!shadow><white>⑧</white></!shadow></hover><bold>"),
                new HoverEvent(HoverEvent.Action.SHOW_TEXT, TextComponent.fromLegacyText("node hint")), null);
        var result = PaperChatComponents.convert(line.getExtra().toArray(net.md_5.bungee.api.chat.BaseComponent[]::new), null);
        assertEquals("hi ⑧!", PlainTextComponentSerializer.plainText().serialize(result));
        var emoji = result.children().get(1);
        assertEquals(null, emoji.hoverEvent());
        var glyph = emoji.children().get(0);
        assertNotNull(glyph.hoverEvent());
        assertEquals("使用\":8ball:\"来发送表情\"⑧\"", PlainTextComponentSerializer.plainText().serialize(
                (net.kyori.adventure.text.Component) glyph.hoverEvent().value()));
    }
}

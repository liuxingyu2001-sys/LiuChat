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
}

package com.liu.liuchat.service;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import com.liu.liuchat.hook.CraftEngineEmojiHook;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PaperChatComponentsTest {
    @Test void craftEngineGlyphCanUseResolvedContent() {
        Map<String, String> resolved = new LinkedHashMap<>();
        CraftEngineEmojiHook.addMatches(resolved, "你好\uE059", ":happysun:", "\uE059", "ce-json:payload");
        assertEquals("ce-json:payload", resolved.get("\uE059"));
    }

    @Test void craftEngineGlyphRetainsHoverDespiteMessageHint() {
        String glyph = "\uE059";
        String json = "{\"text\":\"" + glyph + "\",\"hoverEvent\":{\"action\":\"show_text\",\"contents\":{\"text\":\"CE 悬停\"}}}";
        Map<String, String> resolved = new LinkedHashMap<>();
        CraftEngineEmojiHook.addMatches(resolved, "前" + glyph + "后", ":happysun:", glyph, "ce-json:" + json);
        TextComponent line = new TextComponent();
        ChatPresentation.appendEmojis(line, "前" + glyph + "后", resolved,
                new HoverEvent(HoverEvent.Action.SHOW_TEXT, TextComponent.fromLegacyText("点击回复")), null);
        var result = PaperChatComponents.convert(line.getExtra().toArray(net.md_5.bungee.api.chat.BaseComponent[]::new), null);
        var emoji = result.children().get(1);
        assertEquals("CE 悬停", PlainTextComponentSerializer.plainText().serialize(
                (net.kyori.adventure.text.Component) emoji.hoverEvent().value()));
    }

    @Test void malformedShortcutUrlDoesNotBreakChatDelivery() throws Exception {
        var method = ChatPresentation.class.getDeclaredMethod("action", String.class, String.class, String.class);
        method.setAccessible(true);
        assertEquals(null, method.invoke(null, "", "", "https://www.mc99.tp["));
        assertEquals("https://www.mc99.top", ((ClickEvent) method.invoke(null, "", "", "https://www.mc99.top"))
                .getValue());
    }

    @Test void gradientUrlRemainsClickable() {
        String colored = ProfileChatColor.apply("<gradient:#1AFFF0:#2EA4FF>",
                "看https://www.mc99.top 接着聊", "[i]");
        TextComponent line = new TextComponent();
        ChatPresentation.appendAutoLinks(line, colored, null, null);
        var links = line.getExtra().stream().filter(part -> part.getClickEvent() != null).toList();
        assertEquals(1, links.size());
        assertEquals("https://www.mc99.top", links.get(0).getClickEvent().getValue());
    }

    @Test void autoLinksKeepPunctuationAndOpenExpectedUrl() {
        TextComponent line = new TextComponent();
        ChatPresentation.appendAutoLinks(line, "访问 https://example.com/path?q=1, 或 www.example.org! 普通文本",
                null, null);
        var parts = PaperChatComponents.convert(line.getExtra().toArray(net.md_5.bungee.api.chat.BaseComponent[]::new), null);
        assertEquals("访问 https://example.com/path?q=1, 或 www.example.org! 普通文本",
                PlainTextComponentSerializer.plainText().serialize(parts));
        var link = parts.children().get(1);
        assertEquals(net.kyori.adventure.text.event.ClickEvent.openUrl("https://example.com/path?q=1"),
                link.clickEvent());
        var wwwLink = parts.children().get(3);
        assertEquals(net.kyori.adventure.text.event.ClickEvent.openUrl("https://www.example.org"),
                wwwLink.clickEvent());
    }

    @Test void preservesClickOnNonUrlTextButUsesOpenUrlOnLinks() {
        TextComponent line = new TextComponent();
        var inherited = new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/test");
        ChatPresentation.appendAutoLinks(line, "前缀 https://example.net 后缀", null, inherited);
        var parts = line.getExtra();
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, parts.get(0).getClickEvent().getAction());
        assertEquals(ClickEvent.Action.OPEN_URL, parts.get(1).getClickEvent().getAction());
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, parts.get(2).getClickEvent().getAction());
    }

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

    @Test void craftEngineJsonKeepsImageFontAndHover() {
        String json = "{\"text\":\"\ue059\",\"font\":\"minecraft:default\",\"hoverEvent\":{\"action\":\"show_text\",\"contents\":{\"text\":\"表情 :happysun:\"}}}";
        TextComponent line = new TextComponent();
        ChatPresentation.appendEmojis(line, "hi :happysun:!", java.util.Map.of(":happysun:", "ce-json:" + json),
                null, null);
        var result = PaperChatComponents.convert(line.getExtra().toArray(net.md_5.bungee.api.chat.BaseComponent[]::new), null);
        assertEquals("hi \ue059!", PlainTextComponentSerializer.plainText().serialize(result));
        var glyph = result.children().get(1);
        assertEquals(net.kyori.adventure.key.Key.key("minecraft:default"), glyph.font());
        assertEquals("表情 :happysun:", PlainTextComponentSerializer.plainText().serialize(
                (net.kyori.adventure.text.Component) glyph.hoverEvent().value()));
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

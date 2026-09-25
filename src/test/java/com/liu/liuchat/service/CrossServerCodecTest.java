package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 跨服消息编解码往返测试 —— 协议是对端兼容性最容易出错的地方。
 * <p>
 * 真实链路（两代代理源码比对确认同构）：
 * 服务端(Forward|mode|TAG|len|data) → 代理剥头 → 目标服(TAG|len|data)。
 * {@link #proxyHop} 按 BungeeCord DownstreamBridge / Velocity
 * BungeeCordMessageResponder 的实际产出模拟代理那一步。
 */
class CrossServerCodecTest {

    // ---------------- 各类型往返 ----------------

    @Test
    void itemAnnouncementRoundTrip() throws IOException {
        String snapshot = "item:\n  type: DIAMOND_SWORD\n  enchants:\n    SHARPNESS: 5\n";
        var announcement = assertInstanceOf(CrossServerCodec.Inbound.ItemAnnouncement.class,
                CrossServerCodec.decodeInbound(proxyHop(CrossServerCodec.encodeItemAnnouncement(
                        "lobby", "uuid-1234", "Alice", "§a强化成功 %item%!", snapshot), "ALL")));
        assertEquals("lobby", announcement.origin());
        assertEquals("uuid-1234", announcement.uuid());
        assertEquals("Alice", announcement.name());
        assertEquals("§a强化成功 %item%!", announcement.template());
        assertEquals(snapshot, announcement.snapshot());
    }

    @Test
    void announcementRoundTrip() throws IOException {
        var component = new net.md_5.bungee.api.chat.TextComponent("强化成功");
        component.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_ITEM,
                new net.md_5.bungee.api.chat.hover.content.Item("minecraft:diamond_sword", 1, null)));
        String json = net.md_5.bungee.chat.ComponentSerializer.toString(component);
        var announcement = assertInstanceOf(CrossServerCodec.Inbound.Announcement.class,
                CrossServerCodec.decodeInbound(proxyHop(CrossServerCodec.encodeAnnouncement("lobby", json), "ALL")));
        assertEquals("lobby", announcement.origin());
        var restored = net.md_5.bungee.chat.ComponentSerializer.parse(announcement.componentsJson());
        assertEquals("强化成功", net.md_5.bungee.api.chat.BaseComponent.toPlainText(restored));
        assertEquals(net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_ITEM, restored[0].getHoverEvent().getAction());
    }

    @Test
    void chatRoundTrip() throws IOException {
        byte[] outbound = CrossServerCodec.encodeChat(
                "lobby", "uuid-1234", "Notch", "hello &a跨服消息", "snapshot", "values: %{papi.key}%", "Nickname");

        var decoded = CrossServerCodec.decodeInbound(proxyHop(outbound, expectedMode("ALL")));

        CrossServerCodec.Inbound.ChatMessage chat =
                assertInstanceOf(CrossServerCodec.Inbound.ChatMessage.class, decoded);
        assertEquals("lobby", chat.originServer());
        assertEquals("uuid-1234", chat.uuid());
        assertEquals("Notch", chat.playerName());
        assertEquals("hello &a跨服消息", chat.message());
        assertEquals("snapshot", chat.itemData());
        assertEquals("values: %{papi.key}%", chat.placeholders());
        assertEquals("Nickname", chat.nick());
    }

    @Test
    void tellRoundTrip() throws IOException {
        byte[] outbound = CrossServerCodec.encodeTell(
                "msg-1", "lobby", "Alice", "Bob", "跨服私聊内容",
                "uuid-alice", "world", "values: snapshot", "§aHero");

        var decoded = CrossServerCodec.decodeInbound(proxyHop(outbound, expectedMode("ALL")));

        CrossServerCodec.Inbound.TellMessage tell =
                assertInstanceOf(CrossServerCodec.Inbound.TellMessage.class, decoded);
        assertEquals("msg-1", tell.msgId());
        assertEquals("lobby", tell.originServer());
        assertEquals("Alice", tell.senderName());
        assertEquals("Bob", tell.targetName());
        assertEquals("跨服私聊内容", tell.message());
        assertEquals("uuid-alice", tell.uuid());
        assertEquals("world", tell.world());
        assertEquals("values: snapshot", tell.placeholders());
        assertEquals("§aHero", tell.nick());
        assertEquals("", tell.itemData());
    }

    @Test
    void tellItemSnapshotRoundTrip() throws IOException {
        String snapshot = "item:\n  type: DIAMOND_SWORD\n";
        byte[] outbound = CrossServerCodec.encodeTell(
                "msg-2", "lobby", "Alice", "Bob", "看 [i]", "uuid-alice", "world", "", "Alice", snapshot);
        var tell = assertInstanceOf(CrossServerCodec.Inbound.TellMessage.class,
                CrossServerCodec.decodeInbound(proxyHop(outbound, "ALL")));
        assertEquals("看 [i]", tell.message());
        assertEquals(snapshot, tell.itemData());
    }

    @Test
    void presencePacketsRoundTripAndRejectInvalidBatches() throws IOException {
        var presence = assertInstanceOf(CrossServerCodec.Inbound.Presence.class,
                CrossServerCodec.decodeInbound(proxyHop(
                        CrossServerCodec.encodePresence("lobby", java.util.List.of("Alice", "Bob")), "ALL")));
        assertEquals("lobby", presence.server());
        assertEquals(java.util.List.of("Alice", "Bob"), presence.names());
        var quit = assertInstanceOf(CrossServerCodec.Inbound.PresenceQuit.class,
                CrossServerCodec.decodeInbound(proxyHop(
                        CrossServerCodec.encodePresenceQuit("lobby", "Alice"), "ALL")));
        assertEquals("Alice", quit.name());
        var request = assertInstanceOf(CrossServerCodec.Inbound.PresenceRequest.class,
                CrossServerCodec.decodeInbound(proxyHop(
                        CrossServerCodec.encodePresenceRequest("game"), "ALL")));
        assertEquals("game", request.server());
        assertThrows(IOException.class, () -> CrossServerCodec.encodePresence("lobby", java.util.List.of()));
        assertThrows(IOException.class, () -> CrossServerCodec.encodePresence("lobby",
                java.util.Collections.nCopies(CrossServerCodec.MAX_PRESENCE_NAMES + 1, "a")));
    }

    @Test
    void tellAckRoundTrip() throws IOException {
        // 回执是定向转发：mode = 发送端子服名，不再是 ALL
        byte[] outbound = CrossServerCodec.encodeTellAck("msg-9", "game", "lobby");

        var decoded = CrossServerCodec.decodeInbound(proxyHop(outbound, expectedMode("lobby")));

        CrossServerCodec.Inbound.TellAck ack =
                assertInstanceOf(CrossServerCodec.Inbound.TellAck.class, decoded);
        assertEquals("msg-9", ack.msgId());
        assertEquals("game", ack.ackServer());
    }

    // ---------------- 兼容与拒收 ----------------

    @Test
    void acceptsForwardedPrefixFormat() throws IOException {
        // 部分文档/衍生实现会带 "Forwarded" 前缀，同样要兼容
        byte[] inbound = proxyHop(
                CrossServerCodec.encodeChat("game", "u", "Steve", "hi", "", "", ""), expectedMode("ALL"));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF("Forwarded");
            out.write(inbound);
        }

        var decoded = CrossServerCodec.decodeInbound(bytes.toByteArray());
        assertInstanceOf(CrossServerCodec.Inbound.ChatMessage.class, decoded);
    }

    @Test
    void rejectsOldProtocolVersion() throws IOException {
        // 协议 v1 的旧格式（无类型字段）必须被干净拒绝，而不是错位解析
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(payload)) {
            out.writeUTF("1");
            out.writeUTF("lobby");
            out.writeUTF("u");
            out.writeUTF("n");
            out.writeUTF("m");
        }
        assertNull(CrossServerCodec.decodeInbound(
                CrossServerCodec.wrapForward(CrossServerCodec.MODE_ALL, payload.toByteArray())));
    }

    @Test
    void rejectsUnknownType() throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(payload)) {
            out.writeUTF(CrossServerCodec.PROTOCOL);
            out.writeUTF("FUTURE_TYPE");
            out.writeUTF("whatever");
        }
        assertNull(CrossServerCodec.decodeInbound(
                CrossServerCodec.wrapForward(CrossServerCodec.MODE_ALL, payload.toByteArray())));
    }

    @Test
    void ignoresOtherTag() throws IOException {
        // 同一 BungeeCord 通道上其它插件的转发体：第一个 UTF 就不是我们的 TAG
        byte[] inbound = proxyHop(
                CrossServerCodec.encodeChat("sur", "u", "n", "m", "", "", ""), expectedMode("ALL"));
        byte[] patched = patchTag(inbound, "OtherPlugin");

        assertNull(CrossServerCodec.decodeInbound(patched));
    }

    @Test
    void ignoresGarbage() {
        assertNull(CrossServerCodec.decodeInbound(new byte[0]));
        assertNull(CrossServerCodec.decodeInbound(new byte[]{0x50, 0x61, 0x73}));
        assertNull(CrossServerCodec.decodeInbound(new byte[]{(byte) 0xFF, (byte) 0xFF}));
    }

    @Test
    void forwardHeaderLayout() throws IOException {
        byte[] payload = CrossServerCodec.encodeChat("s", "u", "n", "m", "", "", "");
        // payload 内含类型字段，这里只验证发送侧外层头：
        // UTF("Forward") + UTF(mode) + UTF("LiuChat") + ushort
        byte[] wrapped = CrossServerCodec.wrapForward("ALL", payload);
        int headerSize = (2 + 7) + (2 + 3) + (2 + 7) + 2;
        assertEquals(headerSize + payload.length, wrapped.length);
        assertArrayEquals(payload,
                java.util.Arrays.copyOfRange(wrapped, headerSize, wrapped.length));
    }

    @Test
    void rejectsOversizedForwardPacket() {
        assertThrows(IOException.class, () -> CrossServerCodec.encodeChat(
                "lobby", "uuid", "Alice", "x".repeat(33000), "", "", ""));
        assertThrows(IOException.class, () -> CrossServerCodec.encodeTell(
                "id", "lobby", "Alice", "Bob", "x".repeat(33000), "u", "world", "", ""));
    }

    @Test
    void rejectsTruncatedAndTrailingPackets() throws IOException {
        byte[] inbound = proxyHop(
                CrossServerCodec.encodeChat("lobby", "u", "Alice", "hi", "", "", ""), expectedMode("ALL"));
        assertNull(CrossServerCodec.decodeInbound(java.util.Arrays.copyOf(inbound, inbound.length - 1)));
        assertNull(CrossServerCodec.decodeInbound(java.util.Arrays.copyOf(inbound, inbound.length + 1)));
    }

    @Test
    void hornAndMuteControlsRoundTrip() throws IOException {
        var horn = assertInstanceOf(CrossServerCodec.Inbound.Horn.class,
                CrossServerCodec.decodeInbound(proxyHop(
                        CrossServerCodec.encodeHorn("lobby", "u", "Alice", "all servers"), "ALL")));
        assertEquals("all servers", horn.message());
        var mute = assertInstanceOf(CrossServerCodec.Inbound.Mute.class,
                CrossServerCodec.decodeInbound(proxyHop(
                        CrossServerCodec.encodeMute("uuid", "Alice", 1234L, "reason", "Admin"), "ALL")));
        assertEquals(1234L, mute.expires());
        assertEquals("reason", mute.reason());
        var unmute = assertInstanceOf(CrossServerCodec.Inbound.Unmute.class,
                CrossServerCodec.decodeInbound(proxyHop(CrossServerCodec.encodeUnmute("uuid"), "ALL")));
        assertEquals("uuid", unmute.uuid());
    }

    @Test
    void rejectsTrailingPayloadBytes() throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(payload)) {
            out.writeUTF(CrossServerCodec.PROTOCOL);
            out.writeUTF(CrossServerCodec.TYPE_UNMUTE);
            out.writeUTF("uuid");
            out.writeByte(42);
        }
        byte[] outbound = CrossServerCodec.wrapForward("ALL", payload.toByteArray());
        assertNull(CrossServerCodec.decodeInbound(proxyHop(outbound, "ALL")));
    }

    // ---------------- 测试工具 ----------------

    private static String expectedMode(String mode) {
        return mode;
    }

    /**
     * 模拟代理转发：读掉 Forward/mode，按两代代理的实际产出
     * （channel | len | data，无 Forwarded 前缀）重装；同时校验 mode 符合预期。
     */
    private static byte[] proxyHop(byte[] outbound, String expectedMode) throws IOException {
        String channel;
        byte[] payload;
        try (DataInputStream in = new DataInputStream(
                new java.io.ByteArrayInputStream(outbound))) {
            assertEquals("Forward", in.readUTF());
            assertEquals(expectedMode, in.readUTF());
            channel = in.readUTF();
            int length = in.readUnsignedShort();
            payload = in.readNBytes(length);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(channel);
            out.writeShort(payload.length);
            out.write(payload);
        }
        return bytes.toByteArray();
    }

    /** 把转发体里的 TAG 换成别的（模拟其它插件复用同一通道） */
    private static byte[] patchTag(byte[] inbound, String newTag) throws IOException {
        String channel;
        byte[] payload;
        try (DataInputStream in = new DataInputStream(
                new java.io.ByteArrayInputStream(inbound))) {
            channel = in.readUTF();
            int length = in.readUnsignedShort();
            payload = in.readNBytes(length);
        }
        assertEquals(CrossServerCodec.TAG, channel);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(newTag);
            out.writeShort(payload.length);
            out.write(payload);
        }
        return bytes.toByteArray();
    }
}

package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 跨服消息编解码往返测试 —— 协议是对端兼容性最容易出错的地方。
 * <p>
 * 真实链路（两代代理源码比对确认同构）：
 * 服务端(Forward|ALL|TAG|len|data) → 代理剥头 → 目标服(TAG|len|data)。
 * {@link #proxyHop} 按 BungeeCord DownstreamBridge / Velocity
 * BungeeCordMessageResponder 的实际产出模拟代理那一步。
 */
class CrossServerCodecTest {

    @Test
    void roundTripThroughProxy() throws IOException {
        byte[] outbound = CrossServerCodec.encodeForward(
                "lobby", "uuid-1234", "Notch", "hello &a跨服消息");

        CrossServerCodec.Decoded decoded =
                CrossServerCodec.decodeInbound(proxyHop(outbound));

        assertEquals("lobby", decoded.originServer());
        assertEquals("uuid-1234", decoded.uuid());
        assertEquals("Notch", decoded.playerName());
        assertEquals("hello &a跨服消息", decoded.message());
    }

    @Test
    void roundTripWithForwardedPrefix() throws IOException {
        // 部分文档/衍生实现会带 "Forwarded" 前缀，同样要兼容
        byte[] outbound = CrossServerCodec.encodeForward("game", "u", "Steve", "hi");

        CrossServerCodec.Decoded decoded =
                CrossServerCodec.decodeInbound(withForwardedPrefix(proxyHop(outbound)));

        assertEquals("game", decoded.originServer());
        assertEquals("Steve", decoded.playerName());
        assertEquals("hi", decoded.message());
    }

    @Test
    void ignoresOtherTag() throws IOException {
        // 同一 BungeeCord 通道上其它插件的转发体：第一个 UTF 就不是我们的 TAG
        byte[] outbound = CrossServerCodec.encodeForward("sur", "u", "n", "m");
        byte[] inbound = patchTag(proxyHop(outbound), "OtherPlugin");

        assertNull(CrossServerCodec.decodeInbound(inbound));
    }

    @Test
    void ignoresGarbage() {
        assertNull(CrossServerCodec.decodeInbound(new byte[0]));
        assertNull(CrossServerCodec.decodeInbound(new byte[]{0x50, 0x61, 0x73}));
        assertNull(CrossServerCodec.decodeInbound(new byte[]{(byte) 0xFF, (byte) 0xFF}));
    }

    @Test
    void payloadLengthMatches() throws IOException {
        byte[] payload = CrossServerCodec.encodePayload("sur", "u", "n", "m");
        byte[] wrapped = CrossServerCodec.wrapForward(payload);
        // 发送侧外层头 = UTF("Forward") + UTF("ALL") + UTF("LiuChat") + ushort
        int headerSize = (2 + 7) + (2 + 3) + (2 + 7) + 2;
        assertEquals(headerSize + payload.length, wrapped.length);
        assertArrayEquals(payload,
                java.util.Arrays.copyOfRange(wrapped, headerSize, wrapped.length));
    }

    /**
     * 模拟代理转发：读掉 Forward/ALL，按两代代理的实际产出
     * （channel | len | data，无 Forwarded 前缀）重装。
     */
    private static byte[] proxyHop(byte[] outbound) throws IOException {
        String channel;
        byte[] payload;
        try (DataInputStream in = new DataInputStream(
                new java.io.ByteArrayInputStream(outbound))) {
            assertEquals("Forward", in.readUTF());
            assertEquals("ALL", in.readUTF());
            channel = in.readUTF();
            int length = in.readUnsignedShort();
            payload = in.readNBytes(length);
        }
        return rewrap(channel, payload);
    }

    private static byte[] withForwardedPrefix(byte[] inbound) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF("Forwarded");
            out.write(inbound);
        }
        return bytes.toByteArray();
    }

    private static byte[] patchTag(byte[] inbound, String newTag) throws IOException {
        byte[] payload = extractPayload(stripChannel(inbound));
        return rewrap(newTag, payload);
    }

    /** 去掉外层通道名，留下 len|data */
    private static byte[] stripChannel(byte[] inbound) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new java.io.ByteArrayInputStream(inbound))) {
            in.readUTF();
            int length = in.readUnsignedShort();
            byte[] payload = in.readNBytes(length);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeShort(length);
                out.write(payload);
            }
            return bytes.toByteArray();
        }
    }

    private static byte[] extractPayload(byte[] lengthPrefixed) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new java.io.ByteArrayInputStream(lengthPrefixed))) {
            int length = in.readUnsignedShort();
            return in.readNBytes(length);
        }
    }

    private static byte[] rewrap(String channel, byte[] payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(channel);
            out.writeShort(payload.length);
            out.write(payload);
        }
        return bytes.toByteArray();
    }
}

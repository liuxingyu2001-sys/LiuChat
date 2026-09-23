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
 * 真实链路：服务端(Forward) → 代理剥头重包 → 目标服(Forwarded)，
 * {@link #proxyHop} 模拟代理那一步。
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
    void ignoresOtherTag() throws IOException {
        // 同一 BungeeCord 通道上其它插件的 Forwarded 消息
        byte[] foreign = CrossServerCodec.encodeForward("sur", "u", "n", "m");
        byte[] inbound = patchTag(proxyHop(foreign), "OtherPlugin");

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
        // 外层头 = UTF("Forward") + UTF("ALL") + UTF("LiuChat") + ushort
        int headerSize = (2 + 7) + (2 + 3) + (2 + 7) + 2;
        assertEquals(headerSize + payload.length, wrapped.length);
        assertArrayEquals(payload,
                java.util.Arrays.copyOfRange(wrapped, headerSize, wrapped.length));
    }

    /** 模拟代理：读掉 Forward/ALL/TAG 头，按 Forwarded 格式重新打包 */
    private static byte[] proxyHop(byte[] outbound) throws IOException {
        byte[] payload;
        try (DataInputStream in = new DataInputStream(
                new java.io.ByteArrayInputStream(outbound))) {
            assertEquals("Forward", in.readUTF());
            assertEquals("ALL", in.readUTF());
            assertEquals(CrossServerCodec.TAG, in.readUTF());
            int length = in.readUnsignedShort();
            payload = in.readNBytes(length);
        }
        return rewrapForwarded(payload);
    }

    private static byte[] rewrapForwarded(byte[] payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF("Forwarded");
            out.writeUTF(CrossServerCodec.TAG);
            out.writeShort(payload.length);
            out.write(payload);
        }
        return bytes.toByteArray();
    }

    /** 把 Forwarded 包里的 TAG 换成别的（模拟其它插件复用同一通道） */
    private static byte[] patchTag(byte[] inbound, String newTag) throws IOException {
        byte[] payload;
        try (DataInputStream in = new DataInputStream(
                new java.io.ByteArrayInputStream(inbound))) {
            assertEquals("Forwarded", in.readUTF());
            assertEquals(CrossServerCodec.TAG, in.readUTF());
            int length = in.readUnsignedShort();
            payload = in.readNBytes(length);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF("Forwarded");
            out.writeUTF(newTag);
            out.writeShort(payload.length);
            out.write(payload);
        }
        return bytes.toByteArray();
    }
}

package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiChatSnapshotTest {
    private static final String NAME = "bot";
    private static final String UUID_STRING = PublicChatAiService.aiUuid(NAME).toString();
    private static final UUID HEAD = UUID.fromString("6ff311f1-f729-30f2-b2f7-292c95fdc223");
    private static final String FORMAT = "&e主城 &f[<gradient:#afd9c2:#97e1cb>久久新生&f] ${head}${player}: ${message}";

    @Test void sourceAppearanceSurvivesCrossServerPacket() throws IOException {
        String snapshot = AiChatSnapshot.encode(FORMAT, HEAD);
        byte[] forwarded = CrossServerCodec.encodeChat("99", UUID_STRING, NAME, "你好", "", snapshot, NAME);
        var inbound = (CrossServerCodec.Inbound.ChatMessage) CrossServerCodec.decodeInbound(proxyHop(forwarded));
        var appearance = AiChatSnapshot.decode(inbound.placeholders(), inbound.playerName(), inbound.uuid());
        assertEquals(FORMAT, appearance.format());
        assertEquals(HEAD, appearance.headUuid());
        assertTrue(PublicChatAiService.formatLine(appearance.format(), NAME, inbound.message()).contains("主城"));
    }

    @Test void rejectsOrdinaryPlayersAndInvalidSnapshots() {
        String snapshot = AiChatSnapshot.encode(FORMAT, HEAD);
        assertNull(AiChatSnapshot.decode(snapshot, "Steve", UUID.randomUUID().toString()));
        assertNull(AiChatSnapshot.decode(snapshot, NAME, UUID.randomUUID().toString()));
        assertNull(AiChatSnapshot.decode("values: normal placeholders", NAME, UUID_STRING));
        assertNull(AiChatSnapshot.decode("liuchat-ai-v1:invalid:bad-uuid", NAME, UUID_STRING));
    }

    private static byte[] proxyHop(byte[] outbound) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(outbound))) {
            assertEquals("Forward", in.readUTF());
            assertEquals("ALL", in.readUTF());
            String tag = in.readUTF();
            int length = in.readUnsignedShort();
            byte[] payload = in.readNBytes(length);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeUTF(tag);
                out.writeShort(length);
                out.write(payload);
            }
            return bytes.toByteArray();
        }
    }
}

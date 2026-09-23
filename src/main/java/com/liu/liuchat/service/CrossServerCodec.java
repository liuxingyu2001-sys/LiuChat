package com.liu.liuchat.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 跨服聊天消息的编解码（纯 Java，无 Bukkit 依赖，可单测）。
 * <p>
 * 协议走 BungeeCord plugin messaging 标准：
 * <pre>
 * 发送（服务端 → 代理）:  UTF "Forward" | UTF "ALL" | UTF TAG | ushort len | payload
 * 接收（代理 → 服务端）:  UTF "Forwarded" | UTF TAG | ushort len | payload
 * payload:               UTF 协议版本 | UTF 发送端子服 | UTF 玩家uuid | UTF 玩家名 | UTF 消息文本
 * </pre>
 * 发送端子服已按权限处理好消息文本（该转的颜色已转），接收端原样渲染。
 */
public final class CrossServerCodec {

    /** plugin.yml / Bukkit 的 BungeeCord 通道名 */
    public static final String BUNGEE_CHANNEL = "BungeeCord";
    /** 自定义子通道标签，与其它插件的跨服消息区分开 */
    public static final String TAG = "LiuChat";
    /** 协议版本，不兼容时对端解析失败直接丢弃 */
    public static final String PROTOCOL = "1";

    private CrossServerCodec() {
    }

    /** 编码内部 payload */
    public static byte[] encodePayload(String originServer, String uuid, String playerName, String message)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(PROTOCOL);
            out.writeUTF(originServer);
            out.writeUTF(uuid);
            out.writeUTF(playerName);
            out.writeUTF(message);
        }
        return bytes.toByteArray();
    }

    /** 包上 BungeeCord "Forward ALL" 外层，交给任一在线玩家连接发给代理 */
    public static byte[] wrapForward(byte[] payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF("Forward");
            out.writeUTF("ALL");
            out.writeUTF(TAG);
            out.writeShort(payload.length);
            out.write(payload);
        }
        return bytes.toByteArray();
    }

    /** 一步完成编码：payload + Forward 外层 */
    public static byte[] encodeForward(String originServer, String uuid, String playerName, String message)
            throws IOException {
        return wrapForward(encodePayload(originServer, uuid, playerName, message));
    }

    /**
     * 解析代理转发进来的完整数据包。
     *
     * @return 不是本插件的消息（或格式非法）返回 null
     */
    public static Decoded decodeInbound(byte[] packet) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(packet))) {
            if (!"Forwarded".equals(in.readUTF())) {
                return null;
            }
            if (!TAG.equals(in.readUTF())) {
                return null;
            }
            int length = in.readUnsignedShort();
            byte[] payload = in.readNBytes(length);
            if (payload.length != length) {
                return null;
            }
            return decodePayload(payload);
        } catch (IOException | RuntimeException e) {
            // 畸形数据（同通道上还有其它插件的消息）直接忽略
            return null;
        }
    }

    private static Decoded decodePayload(byte[] payload) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (!PROTOCOL.equals(in.readUTF())) {
                return null;
            }
            return new Decoded(in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF());
        }
    }

    /** 解码结果；uuid 预留（后续屏蔽列表/频道跨服判断用） */
    public record Decoded(String originServer, String uuid, String playerName, String message) {
    }
}

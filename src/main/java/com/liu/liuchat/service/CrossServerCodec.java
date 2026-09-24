package com.liu.liuchat.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 跨服消息编解码（纯 Java，无 Bukkit 依赖，可单测）。
 * <p>
 * 协议走 BungeeCord plugin messaging 标准（发送格式由 BungeeCord wiki 定义，
 * 转发体格式经官方两代代理源码比对确认<b>完全一致</b>）：
 * <pre>
 * 发送（服务端 → 代理）:  UTF "Forward" | UTF mode | UTF TAG | ushort len | payload
 * 转发（代理 → 目标服）:  UTF TAG      | ushort len | payload
 *
 * mode:  "ALL" = 除发送端外的所有子服；具体子服名 = 仅该服（回执定向用）
 *
 * payload: UTF 协议版本 | UTF 类型 | 类型字段...
 *   CHAT     群聊广播:   UTF 发送端子服 | UTF 玩家uuid | UTF 玩家名 | UTF 消息文本 | UTF 物品快照
 *   TELL     跨服私聊:   UTF msgId | UTF 发送端子服 | UTF 发送者 | UTF 目标 | UTF 消息文本 | UTF uuid | UTF world | UTF 占位符快照 | UTF 昵称
 *   TELL_ACK 私聊回执:   UTF msgId | UTF 应答子服
 * </pre>
 * 转发体<b>没有 "Forwarded" 前缀</b>——BungeeCord 的 DownstreamBridge 与 Velocity 的
 * BungeeCordMessageResponder 都把通道名写在第一个 UTF（早期 wiki 文档写法不同，
 * {@link #decodeInbound} 两种形态都兼容）。颜色权限由发送端裁决后原样传输，
 * 接收端不重复判断。
 */
public final class CrossServerCodec {

    /** plugin.yml / Bukkit 的 BungeeCord 通道名 */
    public static final String BUNGEE_CHANNEL = "BungeeCord";
    /** 自定义子通道标签，与其它插件的跨服消息区分开 */
    public static final String TAG = "LiuChat";
    /** 协议版本；格式变化时递增，旧版本对端解析失败直接丢弃 */
    public static final String PROTOCOL = "6";
    /** 转发给除发送端外的所有子服 */
    public static final String MODE_ALL = "ALL";

    public static final String TYPE_CHAT = "CHAT";
    public static final String TYPE_TELL = "TELL";
    public static final String TYPE_TELL_ACK = "TELL_ACK";
    public static final String TYPE_HORN = "HORN";
    public static final String TYPE_MUTE = "MUTE";
    public static final String TYPE_UNMUTE = "UNMUTE";

    /**
     * Bukkit 将旧通道名与 namespaced 名归一化为同一个注册项；
     * 回调参数可能使用其中任意一个名字。
     */
    public static final String[] INCOMING_CHANNELS = {"BungeeCord", "bungeecord:main"};

    /** Bukkit plugin message 限制为 32766 字节（包括 Forward 外层）。 */
    private static final int MAX_PACKET_SIZE = 32766;

    private static volatile String sharedSecret = "";

    public static void setSharedSecret(String secret) {
        sharedSecret = secret == null ? "" : secret;
    }

    private static byte[] authenticate(byte[] payload) throws IOException {
        if (sharedSecret.isBlank()) return payload;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sharedSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal(payload);
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(result)) {
                out.write(payload);
                out.writeUTF(java.util.HexFormat.of().formatHex(signature));
            }
            return result.toByteArray();
        } catch (Exception e) {
            throw new IOException("跨服消息签名失败", e);
        }
    }

    private static byte[] verify(byte[] packet) {
        try {
            if (sharedSecret.isBlank()) return packet;
            if (packet.length < 66) return null;
            int signatureStart = packet.length - 66;
            byte[] body = java.util.Arrays.copyOf(packet, signatureStart);
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(packet, signatureStart, 66))) {
                String hex = in.readUTF();
                if (in.available() != 0 || hex.length() != 64) return null;
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(sharedSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
                return MessageDigest.isEqual(mac.doFinal(body), java.util.HexFormat.of().parseHex(hex)) ? body : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- 发送侧编码 ----------------

    /** 群聊广播包（mode=ALL） */
    public static byte[] encodeChat(String originServer, String uuid, String playerName, String message,
                                    String itemData, String placeholders, String nick)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(PROTOCOL);
            out.writeUTF(TYPE_CHAT);
            out.writeUTF(originServer);
            out.writeUTF(uuid);
            out.writeUTF(playerName);
            out.writeUTF(message);
            out.writeUTF(itemData);
            out.writeUTF(placeholders);
            out.writeUTF(nick);
        }
        return wrapForward(MODE_ALL, bytes.toByteArray());
    }

    /** 跨服私聊包（mode=ALL；只有目标所在服会落地投递） */
    public static byte[] encodeTell(String msgId, String originServer,
                                    String senderName, String targetName, String message,
                                    String uuid, String world, String placeholders, String nick)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(PROTOCOL);
            out.writeUTF(TYPE_TELL);
            out.writeUTF(msgId);
            out.writeUTF(originServer);
            out.writeUTF(senderName);
            out.writeUTF(targetName);
            out.writeUTF(message);
            out.writeUTF(uuid);
            out.writeUTF(world);
            out.writeUTF(placeholders);
            out.writeUTF(nick);
        }
        return wrapForward(MODE_ALL, bytes.toByteArray());
    }

    /** 私聊回执（mode=发送端子服名，定向送回，其它服不收） */
    public static byte[] encodeTellAck(String msgId, String ackServer, String replyToServer)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(PROTOCOL);
            out.writeUTF(TYPE_TELL_ACK);
            out.writeUTF(msgId);
            out.writeUTF(ackServer);
        }
        return wrapForward(replyToServer, bytes.toByteArray());
    }

    public static byte[] encodeHorn(String origin, String uuid, String name, String message) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(PROTOCOL);
            out.writeUTF(TYPE_HORN);
            out.writeUTF(origin);
            out.writeUTF(uuid);
            out.writeUTF(name);
            out.writeUTF(message);
        }
        return wrapForward(MODE_ALL, bytes.toByteArray());
    }

    public static byte[] encodeMute(String uuid, String name, long expires, String reason, String operator)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(PROTOCOL);
            out.writeUTF(TYPE_MUTE);
            out.writeUTF(uuid);
            out.writeUTF(name);
            out.writeLong(expires);
            out.writeUTF(reason);
            out.writeUTF(operator);
        }
        return wrapForward(MODE_ALL, bytes.toByteArray());
    }

    public static byte[] encodeUnmute(String uuid) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(PROTOCOL);
            out.writeUTF(TYPE_UNMUTE);
            out.writeUTF(uuid);
        }
        return wrapForward(MODE_ALL, bytes.toByteArray());
    }

    /** 包上 BungeeCord "Forward" 外层，交给任一在线玩家连接发给代理 */
    public static byte[] wrapForward(String mode, byte[] payload) throws IOException {
        payload = authenticate(payload);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF("Forward");
            out.writeUTF(mode);
            out.writeUTF(TAG);
            out.writeShort(payload.length);
            out.write(payload);
        }
        byte[] packet = bytes.toByteArray();
        if (packet.length > MAX_PACKET_SIZE) {
            throw new IOException("Plugin message exceeds Bukkit limit: " + packet.length);
        }
        return packet;
    }

    // ---------------- 接收侧解码 ----------------

    /**
     * 解析代理转发进来的完整数据包。
     * <p>
     * 官方格式：第一个 UTF 就是通道名（BungeeCord DownstreamBridge 与
     * Velocity 均只写 channel|len|data）；部分文档/衍生实现会多一个
     * "Forwarded" 前缀，同样兼容。
     *
     * @return 不是本插件的消息（或格式非法/版本不符）返回 null
     */
    public static Inbound decodeInbound(byte[] packet) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(packet))) {
            String first = in.readUTF();
            String channel = "Forwarded".equals(first) ? in.readUTF() : first;
            if (!TAG.equals(channel)) {
                return null;
            }
            int length = in.readUnsignedShort();
            byte[] payload = in.readNBytes(length);
            if (payload.length != length || in.available() != 0) {
                return null;
            }
            payload = verify(payload);
            return payload == null ? null : decodePayload(payload);
        } catch (IOException | RuntimeException e) {
            // 畸形数据（同通道上还有其它插件的消息）直接忽略
            return null;
        }
    }

    private static Inbound decodePayload(byte[] payload) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (!PROTOCOL.equals(in.readUTF())) {
                return null;
            }
            String type = in.readUTF();
            Inbound decoded = switch (type) {
                case TYPE_CHAT -> new Inbound.ChatMessage(
                        in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF());
                case TYPE_TELL -> new Inbound.TellMessage(
                        in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(),
                        in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF());
                case TYPE_TELL_ACK -> new Inbound.TellAck(in.readUTF(), in.readUTF());
                case TYPE_HORN -> new Inbound.Horn(in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF());
                case TYPE_MUTE -> new Inbound.Mute(in.readUTF(), in.readUTF(), in.readLong(), in.readUTF(), in.readUTF());
                case TYPE_UNMUTE -> new Inbound.Unmute(in.readUTF());
                default -> null;
            };
            return in.available() == 0 ? decoded : null;
        }
    }

    /**
     * 解码结果。字段顺序与类注释里的 payload 表一致。
     * uuid 由发送端填入（后续屏蔽列表等跨服判断可用），回执路由只依赖子服名。
     */
    public sealed interface Inbound
            permits Inbound.ChatMessage, Inbound.TellMessage, Inbound.TellAck,
                    Inbound.Horn, Inbound.Mute, Inbound.Unmute {

        /** 群聊广播 */
        record ChatMessage(String originServer, String uuid, String playerName,
                           String message, String itemData, String placeholders, String nick)
                implements Inbound {
        }

        /** 跨服私聊；目标服用 targetName 本地匹配在线玩家 */
        record TellMessage(String msgId, String originServer,
                           String senderName, String targetName, String message,
                           String uuid, String world, String placeholders, String nick)
                implements Inbound {
        }

        /** 私聊回执：发送端子服凭 msgId 标记送达；ackServer 仅日志用 */
        record TellAck(String msgId, String ackServer) implements Inbound { }
        record Horn(String origin, String uuid, String name, String message) implements Inbound { }
        record Mute(String uuid, String name, long expires, String reason, String operator) implements Inbound { }
        record Unmute(String uuid) implements Inbound { }
    }
}

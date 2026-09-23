package com.liu.liuchat.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 跨服聊天消息的编解码（纯 Java，无 Bukkit 依赖，可单测）。
 * <p>
 * 协议走 BungeeCord plugin messaging 标准（发送格式由 BungeeCord wiki 定义，
 * 转发体格式经官方两代代理源码比对确认<b>完全一致</b>）：
 * <pre>
 * 发送（服务端 → 代理）:  UTF "Forward" | UTF "ALL" | UTF TAG | ushort len | payload
 * 转发（代理 → 目标服）:  UTF TAG      | ushort len | payload
 * payload:               UTF 协议版本 | UTF 发送端子服 | UTF 玩家uuid | UTF 玩家名 | UTF 消息文本
 * </pre>
 * 转发体<b>没有 "Forwarded" 前缀</b>——BungeeCord 的 DownstreamBridge 与 Velocity 的
 * BungeeCordMessageResponder 都把通道名写在第一个 UTF（早期 wiki 文档写法不同，
 * {@link #decodeInbound} 两种形态都兼容）。发送端子服已按权限处理好消息文本，
 * 接收端原样渲染。
 */
public final class CrossServerCodec {

    /** plugin.yml / Bukkit 的 BungeeCord 通道名 */
    public static final String BUNGEE_CHANNEL = "BungeeCord";
    /** 自定义子通道标签，与其它插件的跨服消息区分开 */
    public static final String TAG = "LiuChat";
    /** 协议版本，不兼容时对端解析失败直接丢弃 */
    public static final String PROTOCOL = "1";
    /**
     * 入站监听要覆盖的通道名：Bukkit 的 StandardMessenger.validateAndCorrectChannel
     * 把 "BungeeCord" 与 "bungeecord:main" 互为纠正（注册与派发两边都过同一函数），
     * 两个名字都注册则无论代理/NMS 以哪种形态投递都必命中其一。
     * Velocity 发往 1.13+ 后端时会把 "BungeeCord" 改写为 "bungeecord:main"
     * （PluginMessagePacket#encode），所以现代名必须注册。
     */
    public static final String[] INCOMING_CHANNELS = {"BungeeCord", "bungeecord:main"};

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
     * <p>
     * 官方格式：第一个 UTF 就是通道名（BungeeCord DownstreamBridge 与
     * Velocity 均只写 channel|len|data）；部分文档/衍生实现会多一个
     * "Forwarded" 前缀，同样兼容。
     *
     * @return 不是本插件的消息（或格式非法）返回 null
     */
    public static Decoded decodeInbound(byte[] packet) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(packet))) {
            String first = in.readUTF();
            String channel = "Forwarded".equals(first) ? in.readUTF() : first;
            if (!TAG.equals(channel)) {
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

package com.liu.liuchat.hook.qq;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code qq.yml} 的只读快照。
 *
 * <p>与连接相关的字段会组成 {@link #fingerprint()}：{@code /liuc reload} 时只有
 * 凭证/网关/intents/超时变化才重建 WebSocket，改格式或目标不会打断连接。
 */
public record QqBotSettings(
        boolean enabled,
        String appId,
        String appSecret,
        boolean sandbox,
        String gateway,
        int intents,
        Set<QqTarget.Type> receiveTypes,
        int reconnectSeconds,
        int reconnectMaxSeconds,
        int timeoutSeconds,
        boolean receiveEnabled,
        Set<String> receiveChannels,
        String receiveFormat,
        String fallbackName,
        int receiveMaxLength,
        boolean receiveCrossServer,
        boolean receiveLog,
        boolean logTargets,
        boolean sendEnabled,
        List<QqTarget> sendTargets,
        String sendFormat,
        int sendIntervalMs,
        int sendMaxLength,
        boolean passiveReply,
        int passiveWindowSeconds) {

    /** QQ 开放平台 intents。 */
    public static final int INTENT_GUILD_MESSAGES = 1 << 9;
    public static final int INTENT_DIRECT_MESSAGE = 1 << 12;
    public static final int INTENT_GROUP_AND_C2C = 1 << 25;
    public static final int INTENT_PUBLIC_GUILD_MESSAGES = 1 << 30;

    public QqBotSettings {
        appId = appId == null ? "" : appId.strip();
        appSecret = appSecret == null ? "" : appSecret.strip();
        gateway = gateway == null ? "" : gateway.strip();
        receiveTypes = receiveTypes == null || receiveTypes.isEmpty()
                ? Collections.unmodifiableSet(EnumSet.of(QqTarget.Type.GROUP, QqTarget.Type.C2C))
                : Collections.unmodifiableSet(EnumSet.copyOf(receiveTypes));
        receiveChannels = receiveChannels == null ? Set.of() : Set.copyOf(receiveChannels);
        receiveFormat = receiveFormat == null || receiveFormat.isBlank()
                ? QqBotText.DEFAULT_RECEIVE_FORMAT : receiveFormat;
        fallbackName = fallbackName == null || fallbackName.isBlank()
                ? QqBotText.DEFAULT_FALLBACK_NAME : fallbackName.strip();
        sendTargets = sendTargets == null ? List.of() : List.copyOf(sendTargets);
        sendFormat = sendFormat == null || sendFormat.isBlank()
                ? QqBotText.DEFAULT_SEND_FORMAT : sendFormat;
    }

    public static QqBotSettings load(FileConfiguration config) {
        int extraIntents = intValue(config, "intents-bits", 0, 0, Integer.MAX_VALUE);
        List<String> intentNames = config.getStringList("intents");
        EnumSet<QqTarget.Type> types = EnumSet.noneOf(QqTarget.Type.class);
        int intents = intents(intentNames, types) | extraIntents;

        Set<String> channels = new LinkedHashSet<>();
        for (String raw : config.getStringList("receive.channels")) {
            if (raw != null && !raw.isBlank()) channels.add(raw.strip());
        }

        List<QqTarget> targets = new ArrayList<>();
        for (String raw : config.getStringList("send.targets")) {
            QqTarget target = QqTarget.parse(raw, QqTarget.Type.GROUP);
            if (target != null) targets.add(target);
        }

        return new QqBotSettings(
                config.getBoolean("enable", false),
                config.getString("app-id", ""),
                config.getString("app-secret", ""),
                config.getBoolean("sandbox", false),
                config.getString("connection.gateway", ""),
                intents,
                types,
                intValue(config, "connection.reconnect-seconds", 5, 1, 3600),
                intValue(config, "connection.reconnect-max-seconds", 60, 1, 3600),
                intValue(config, "connection.timeout-seconds", 15, 3, 120),
                config.getBoolean("receive.enable", true),
                channels,
                config.getString("receive.format", QqBotText.DEFAULT_RECEIVE_FORMAT),
                config.getString("receive.fallback-name", QqBotText.DEFAULT_FALLBACK_NAME),
                intValue(config, "receive.max-length", 300, 1, 4000),
                config.getBoolean("receive.cross-server", false),
                config.getBoolean("receive.log", true),
                config.getBoolean("receive.log-targets", true),
                config.getBoolean("send.enable", false),
                targets,
                config.getString("send.format", QqBotText.DEFAULT_SEND_FORMAT),
                intValue(config, "send.interval-ms", 1000, 0, 60000),
                intValue(config, "send.max-length", 2000, 1, 4000),
                config.getBoolean("send.passive-reply", true),
                intValue(config, "send.passive-window-seconds", 240, 0, 300));
    }

    /** 只有凭证齐全且总开关打开才真正连接。 */
    public boolean usable() {
        return enabled && !appId.isEmpty() && !appSecret.isEmpty();
    }

    public boolean receiveType(QqTarget.Type type) {
        return receiveTypes.contains(type);
    }

    /** 连接参数指纹；只包含重连时必须重建的字段。 */
    public String fingerprint() {
        return appId + '\n' + appSecret + '\n' + sandbox + '\n'
                + gateway + '\n' + intents + '\n' + timeoutSeconds + '\n' + reconnectSeconds + '\n'
                + reconnectMaxSeconds;
    }

    /** 解析 intents 名称并同时填充要接收的事件类型。未知名称忽略。 */
    static int intents(List<String> names, Set<QqTarget.Type> types) {
        int bits = 0;
        List<String> values = names == null ? List.of() : names;
        if (values.isEmpty()) values = List.of("group", "c2c");
        for (String raw : values) {
            String name = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
            switch (name) {
                case "group" -> {
                    bits |= INTENT_GROUP_AND_C2C;
                    types.add(QqTarget.Type.GROUP);
                }
                case "c2c" -> {
                    bits |= INTENT_GROUP_AND_C2C;
                    types.add(QqTarget.Type.C2C);
                }
                case "guild" -> {
                    bits |= INTENT_PUBLIC_GUILD_MESSAGES;
                    types.add(QqTarget.Type.CHANNEL);
                }
                case "guild-private" -> {
                    bits |= INTENT_GUILD_MESSAGES;
                    types.add(QqTarget.Type.CHANNEL);
                }
                case "direct" -> {
                    bits |= INTENT_DIRECT_MESSAGE;
                    types.add(QqTarget.Type.DIRECT);
                }
                case "all" -> {
                    bits |= INTENT_GROUP_AND_C2C | INTENT_PUBLIC_GUILD_MESSAGES | INTENT_DIRECT_MESSAGE;
                    types.add(QqTarget.Type.GROUP);
                    types.add(QqTarget.Type.C2C);
                    types.add(QqTarget.Type.CHANNEL);
                    types.add(QqTarget.Type.DIRECT);
                }
                default -> {
                    // 未识别的名称忽略；需要特殊事件时用 intents-bits 直接给位。
                }
            }
        }
        if (types.isEmpty()) {
            bits |= INTENT_GROUP_AND_C2C;
            types.add(QqTarget.Type.GROUP);
            types.add(QqTarget.Type.C2C);
        }
        return bits;
    }

    private static int intValue(ConfigurationSection config, String path, int fallback, int min, int max) {
        Object raw = config.get(path);
        int value = fallback;
        if (raw instanceof Number number) {
            value = number.intValue();
        } else if (raw instanceof String text) {
            try {
                value = Integer.decode(text.strip());
            } catch (NumberFormatException ignored) {
                value = fallback;
            }
        }
        return Math.max(min, Math.min(max, value));
    }
}

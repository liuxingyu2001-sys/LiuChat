package com.liu.liuchat.util;

import java.util.UUID;

/**
 * 控制台 / 命令方块等非玩家来源的虚拟身份。
 * <p>
 * 跨服传输里没有真实的发送者实体，用一个固定的全零 UUID 标识「来自控制台」，
 * 展示名由 config.yml 的 {@code console.name} 决定。
 */
public final class ConsoleIdentity {

    /** 全零 UUID：永远不会与真实玩家 UUID 冲突。 */
    public static final UUID ID = new UUID(0L, 0L);

    private ConsoleIdentity() {
    }
}

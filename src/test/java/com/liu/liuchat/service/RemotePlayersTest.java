package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RemotePlayersTest {
    @Test void mergesLocalAndRemotePlayersWithoutDuplicates() {
        RemotePlayers directory = new RemotePlayers();
        directory.update("game", List.of("Alice", "Bob", "bad name"), 1_000L);
        directory.update("lobby", List.of("alice", "Carol"), 1_000L);
        assertEquals(List.of("Alice", "Bob", "Carol"),
                directory.complete("", List.of("Alice"), 1_001L));
        assertEquals(List.of("Bob"), directory.complete("b", List.of(), 1_001L));
        directory.remove("game", "bOB");
        assertEquals(List.of(), directory.complete("b", List.of(), 1_002L));
    }

    @Test void refreshesAndExpiresRemoteEntries() {
        RemotePlayers directory = new RemotePlayers();
        directory.update("game", List.of("Alice", "Bob"), 0L);
        // 同一轮里的后续分包（到达间隔小于周期阈值）合并，不重置已有名单
        directory.update("game", List.of("Carol"), 500L);
        assertEquals(List.of("Alice", "Bob", "Carol"), directory.complete("", List.of(), 149_999L));
        // 一轮完整名单（间隔大于周期阈值）：不在名单里的人立刻消失，
        // 不再等 TTL —— 否则子服重启/崩溃后旧名单要残留 150 秒，@提及会指向不存在的人
        directory.update("game", List.of("Alice"), 60_000L);
        assertEquals(List.of("Alice"), directory.complete("", List.of(), 149_999L));
        // 该服彻底停发（崩溃）→ 仍由 TTL 兜底过期
        assertEquals(List.of(), directory.complete("", List.of(), 210_000L));
    }

    @Test void neverClearsMidBatchWhenGapExceedsThreshold() {
        // >100 人的服会分批发：满批(100) + 尾批。两批之间若被代理拖了 1 秒以上，
        // 尾批绝不能被当成「新一轮」把满批清掉，否则要少认识 100 个人。
        RemotePlayers directory = new RemotePlayers();
        List<String> head = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) head.add("P" + i);
        directory.update("big", head, 0L);
        directory.update("big", List.of("Tail"), 5_000L);
        assertEquals(101, directory.names(5_500L).size());
        // 满批之后、间隔够久的下一轮才会重置
        directory.update("big", List.of("Fresh"), 70_000L);
        assertEquals(List.of("Fresh"), directory.names(70_500L));
    }

    @Test void discardsStaleRosterFromRestartedServer() {
        RemotePlayers directory = new RemotePlayers();
        directory.update("game", List.of("Old1", "Old2", "Old3"), 0L);
        // 子服重启后立刻重新上报（间隔 > 周期阈值）→ 旧名单整体作废
        directory.update("game", List.of("New1"), 30_000L);
        assertEquals(List.of("New1"), directory.names(31_000L));
        // 没有 quit 包也不会残留
        assertEquals(List.of(), directory.complete("Old", List.of(), 31_000L));
    }

    @Test void snapshotsRemoteNamesForMentions() {
        RemotePlayers directory = new RemotePlayers();
        directory.update("game", List.of("Alice", "Bob"), 0L);
        directory.update("lobby", List.of("Carol"), 0L);
        assertEquals(List.of("Alice", "Bob", "Carol"),
                directory.names(1_000L).stream().sorted().toList());
        assertEquals(List.of(), directory.names(200_000L));
    }
}

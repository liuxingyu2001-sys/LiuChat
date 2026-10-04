package com.liu.liuchat.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Last-seen remote players; expiry handles servers that vanish without a quit packet. */
public final class RemotePlayers {
    private static final long TTL_MILLIS = 150_000L;
    /**
     * 同一份周期名单的多个分包（每个服每 60 秒广播一次，按 100 人一批）
     * 会连续到达；间隔大于该值即认为「新一轮完整名单」，清掉上一轮残留。
     */
    private static final long CYCLE_GAP_MILLIS = 1_000L;
    private record Entry(String name, long seenAt) { }
    private final Map<String, Map<String, Entry>> servers = new HashMap<>();
    /** 各子服最后一次收到名单包的时刻（判定分包属于哪一轮）。 */
    private final Map<String, Long> lastPacketAt = new HashMap<>();
    /** 各子服上一个分包是否为<b>满批</b>（满批后面可能还有分包，此时不能判定新一轮）。 */
    private final Map<String, Boolean> lastBatchFull = new HashMap<>();

    /**
     * 收到一个子服的名单分包。
     * <p>
     * 发送端每轮广播的是<b>完整在线名单</b>，但旧实现只往 map 里 put，
     * 玩家下线后若没收到 PRESENCE_QUIT（服务端崩溃、代理断连都会这样），
     * 旧名字会一直留到 TTL 到期 —— @提及和 tab 补全会指向根本不在的人。
     * 这里按「到达间隔」识别新一轮名单并整体重置，崩溃服的残留最长一个周期即清除。
     */
    public synchronized void update(String server, Collection<String> names, long now) {
        if (server == null || server.isBlank() || server.length() > 64) return;
        Long previous = lastPacketAt.put(server, now);
        // 先无条件记下本批是否满批：注意下面 previousEndedCycle 里有 || 短路，
        // 若把 put 写在同一行，首个分包时根本不会执行，第二个分包会被误判成新一轮。
        boolean thisBatchFull = names.size() >= CrossServerCodec.MAX_PRESENCE_NAMES;
        Boolean previousBatchFull = lastBatchFull.put(server, thisBatchFull);
        // 上一个分包是满批（正好 100 人）时，它可能只是「这一轮的前半截」——
        // 紧跟其后的分包即使间隔超过阈值也必须合并，否则会把前半截清掉、少认识一批人。
        // 代价是「整轮恰好一批满批」的服不享受即时重置，仍由 TTL 兜底（与旧行为一致）。
        boolean previousEndedCycle = previous == null || !Boolean.TRUE.equals(previousBatchFull);
        boolean newCycle = previousEndedCycle
                && (previous == null || now - previous > CYCLE_GAP_MILLIS);
        Map<String, Entry> players = servers.computeIfAbsent(server, ignored -> new HashMap<>());
        if (newCycle) players.clear();
        for (String name : names) {
            if (validName(name)) players.put(name.toLowerCase(Locale.ROOT), new Entry(name, now));
        }
    }

    public synchronized void remove(String server, String name) {
        Map<String, Entry> players = servers.get(server);
        if (players != null && name != null) players.remove(name.toLowerCase(Locale.ROOT));
        if (players == null || players.isEmpty()) {
            lastPacketAt.remove(server);
            lastBatchFull.remove(server);
        }
    }

    public synchronized List<String> complete(String prefix, Collection<String> localNames, long now) {
        String search = prefix.toLowerCase(Locale.ROOT);
        Map<String, String> matches = new TreeMap<>();
        for (Map<String, Entry> players : servers.values()) {
            players.values().removeIf(entry -> now - entry.seenAt() >= TTL_MILLIS);
            for (Entry entry : players.values()) {
                if (entry.name().toLowerCase(Locale.ROOT).startsWith(search))
                    matches.put(entry.name().toLowerCase(Locale.ROOT), entry.name());
            }
        }
        dropOrphanServerState();
        for (String name : localNames) {
            if (name.toLowerCase(Locale.ROOT).startsWith(search))
                matches.put(name.toLowerCase(Locale.ROOT), name);
        }
        return List.copyOf(matches.values());
    }

    /** 快照：所有未过期的远端玩家 ID，供聊天 @ 提及识别其他子服的玩家。 */
    public synchronized List<String> names(long now) {
        for (Map<String, Entry> players : servers.values())
            players.values().removeIf(entry -> now - entry.seenAt() >= TTL_MILLIS);
        dropOrphanServerState();
        List<String> names = new ArrayList<>();
        for (Map<String, Entry> players : servers.values())
            for (Entry entry : players.values()) names.add(entry.name());
        return List.copyOf(names);
    }

    /** 服的名单已被清空/移除时，把它残留的状态键一起回收，免得两张辅助表无限增长。 */
    private void dropOrphanServerState() {
        servers.values().removeIf(Map::isEmpty);
        lastPacketAt.keySet().removeIf(server -> !servers.containsKey(server));
        lastBatchFull.keySet().removeIf(server -> !servers.containsKey(server));
    }

    private static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_.]{1,32}");
    }
}

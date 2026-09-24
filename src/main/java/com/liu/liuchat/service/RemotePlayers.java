package com.liu.liuchat.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Last-seen remote players; expiry handles servers that vanish without a quit packet. */
public final class RemotePlayers {
    private static final long TTL_MILLIS = 150_000L;
    private record Entry(String name, long seenAt) { }
    private final Map<String, Map<String, Entry>> servers = new HashMap<>();

    public void update(String server, Collection<String> names, long now) {
        if (server == null || server.isBlank() || server.length() > 64) return;
        Map<String, Entry> players = servers.computeIfAbsent(server, ignored -> new HashMap<>());
        for (String name : names) {
            if (validName(name)) players.put(name.toLowerCase(Locale.ROOT), new Entry(name, now));
        }
    }

    public void remove(String server, String name) {
        Map<String, Entry> players = servers.get(server);
        if (players != null && name != null) players.remove(name.toLowerCase(Locale.ROOT));
    }

    public List<String> complete(String prefix, Collection<String> localNames, long now) {
        String search = prefix.toLowerCase(Locale.ROOT);
        Map<String, String> matches = new TreeMap<>();
        for (Map<String, Entry> players : servers.values()) {
            players.values().removeIf(entry -> now - entry.seenAt() >= TTL_MILLIS);
            for (Entry entry : players.values()) {
                if (entry.name().toLowerCase(Locale.ROOT).startsWith(search))
                    matches.put(entry.name().toLowerCase(Locale.ROOT), entry.name());
            }
        }
        servers.values().removeIf(Map::isEmpty);
        for (String name : localNames) {
            if (name.toLowerCase(Locale.ROOT).startsWith(search))
                matches.put(name.toLowerCase(Locale.ROOT), name);
        }
        return List.copyOf(matches.values());
    }

    private static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_.]{1,32}");
    }
}

package com.liu.liuchat.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Bounded local public-chat history and verified AI report parser. */
public final class HourlyChatHistory {
    public static final int MAX_MESSAGES = 250;
    private static final int MAX_TEXT = 300;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private int dropped;

    public record Entry(String uuid, String player, Instant time, String text) { }
    public record Snapshot(List<Entry> entries, int dropped) { }
    public record Finding(String uuid, String player, String reason, String evidence) { }

    public synchronized void add(String uuid, String player, String text, Instant time) {
        if (entries.size() == MAX_MESSAGES) {
            entries.removeFirst();
            dropped++;
        }
        String clean = text.replace('\r', ' ').replace('\n', ' ');
        entries.addLast(new Entry(uuid, player, time, clean.substring(0, Math.min(MAX_TEXT, clean.length()))));
    }

    public synchronized Snapshot drain() {
        Snapshot snapshot = new Snapshot(List.copyOf(entries), dropped);
        entries.clear();
        dropped = 0;
        return snapshot;
    }

    public synchronized void restore(Snapshot failed) {
        ArrayDeque<Entry> combined = new ArrayDeque<>(failed.entries());
        combined.addAll(entries);
        entries.clear();
        while (combined.size() > MAX_MESSAGES) {
            combined.removeFirst();
            dropped++;
        }
        entries.addAll(combined);
        dropped += failed.dropped();
    }

    public static String asJson(Snapshot snapshot) {
        JsonArray array = new JsonArray();
        for (Entry entry : snapshot.entries()) {
            JsonObject record = new JsonObject();
            record.addProperty("uuid", entry.uuid());
            record.addProperty("player", entry.player());
            record.addProperty("time", entry.time().toString());
            record.addProperty("message", entry.text());
            array.add(record);
        }
        return array.toString();
    }

    public static List<Finding> findings(String response, Snapshot snapshot) {
        JsonArray array;
        try {
            array = JsonParser.parseString(extractArray(response)).getAsJsonArray();
        } catch (JsonParseException | IllegalStateException ex) {
            throw new IllegalArgumentException("AI 审查结果不是有效的 JSON 数组", ex);
        }
        Set<String> known = new HashSet<>();
        for (Entry entry : snapshot.entries()) known.add(entry.uuid());
        List<Finding> findings = new ArrayList<>();
        Set<String> reported = new HashSet<>();
        for (JsonElement value : array) {
            if (findings.size() >= 30) break;
            if (!value.isJsonObject()) continue;
            JsonObject object = value.getAsJsonObject();
            if (!stringField(object, "uuid") || !stringField(object, "reason") || !stringField(object, "evidence")) continue;
            String uuid = object.get("uuid").getAsString();
            if (!known.contains(uuid) || reported.contains(uuid)) continue;
            String reason = object.get("reason").getAsString();
            String evidence = object.get("evidence").getAsString();
            if (reason.isBlank() || evidence.isBlank() || snapshot.entries().stream()
                    .noneMatch(e -> e.uuid().equals(uuid) && e.text().contains(evidence))) continue;
            reported.add(uuid);
            String player = snapshot.entries().stream().filter(e -> e.uuid().equals(uuid))
                    .findFirst().orElseThrow().player();
            findings.add(new Finding(uuid, player, trim(reason, 160), trim(evidence, 160)));
        }
        return findings;
    }

    private static boolean stringField(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                && object.get(key).getAsJsonPrimitive().isString();
    }

    private static String extractArray(String response) {
        if (response == null || response.length() > 65536) throw new IllegalArgumentException("AI 审查结果为空或过长");
        int start = response.indexOf('[');
        if (start < 0) throw new IllegalArgumentException("AI 审查结果没有 JSON 数组");
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = start; i < response.length(); i++) {
            char c = response.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (quoted && c == '\\') { escaped = true; continue; }
            if (c == '"') { quoted = !quoted; continue; }
            if (quoted) continue;
            if (c == '[') depth++;
            if (c == ']' && --depth == 0) return response.substring(start, i + 1);
        }
        throw new IllegalArgumentException("AI 审查结果中的 JSON 数组不完整");
    }

    private static String trim(String text, int limit) {
        String clean = text.replace('\r', ' ').replace('\n', ' ').replace('§', '&');
        return clean.substring(0, Math.min(limit, clean.length()));
    }
}

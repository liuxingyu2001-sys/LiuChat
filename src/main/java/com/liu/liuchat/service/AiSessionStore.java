package com.liu.liuchat.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 每个助手一份、全服玩家共享的多轮会话。
 * <p>
 * NPC 右键、/lc ask、/lc dialog ai 只要解析到同一个助手名，就共用同一份会话，
 * 任何一个玩家的提问和回答都会留下来给后续玩家当上下文，直到超过上限被裁掉。
 * 内存读写全部在主线程；落盘是异步定时 + 关服同步，重启不丢。
 */
public final class AiSessionStore {

    /** 上下文裁剪与过期上限；messages &lt;= 0 表示关闭上下文。 */
    public record Limits(int messages, int chars, int ttlSeconds) {
        public boolean enabled() { return messages > 0; }
        /** 字符预算，0 = 不限制 */
        boolean unlimitedChars() { return chars <= 0; }
        /** 闲置过期秒数，0 = 永不过期 */
        boolean neverExpires() { return ttlSeconds <= 0; }
    }

    /** 一条问答；answer 为 null 表示请求还没回来，不计入上下文也不落盘。 */
    public static final class Turn {
        private final String assistant;
        private final String question;
        private String answer;

        private Turn(String assistant, String question, String answer) {
            this.assistant = assistant;
            this.question = question;
            this.answer = answer;
        }

        public String assistant() { return assistant; }
        public String question() { return question; }
        public String answer() { return answer; }
    }

    private static final class Session {
        private final ArrayDeque<Turn> turns = new ArrayDeque<>();
        private long lastActivity;
    }

    /** 单条回答落盘/入上下文的长度上限，防止一条超长回答吃掉整个字符预算 */
    private static final int MAX_STORED_ANSWER = 4000;
    private static final int MAX_ASSISTANT_KEY = 48;
    private static final int MAX_SESSIONS = 64;
    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

    private final Path file;
    private final Logger log;
    private final Map<String, Session> sessions = new HashMap<>();
    private boolean dirty;
    private boolean persist = true;

    public AiSessionStore(Path file, Logger log) {
        this.file = file;
        this.log = log;
    }

    /** 是否写入 ai-sessions.json；false = 只留内存，重启即失 */
    public void setPersist(boolean persist) { this.persist = persist; }

    /** 读取上次保存的会话；文件损坏时丢弃并告警，不影响启用。 */
    public synchronized void load() {
        sessions.clear();
        dirty = false;
        if (!persist || file == null || !Files.exists(file)) return;
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                log.warning("AI 会话文件过大，已忽略: " + file);
                return;
            }
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject data = root.getAsJsonObject("sessions");
            if (data == null) return;
            long now = System.currentTimeMillis();
            for (String assistant : data.keySet()) {
                JsonObject value = data.getAsJsonObject(assistant);
                if (value == null) continue;
                Session session = new Session();
                session.lastActivity = value.has("activity") ? value.get("activity").getAsLong() : now;
                if (session.lastActivity <= 0) session.lastActivity = now;
                JsonArray turns = value.getAsJsonArray("turns");
                if (turns != null) for (JsonElement element : turns) {
                    JsonObject turn = element.getAsJsonObject();
                    String question = text(turn, "q");
                    String answer = text(turn, "a");
                    if (question == null || answer == null) continue;
                    session.turns.addLast(new Turn(key(assistant), question, answer));
                }
                if (!session.turns.isEmpty()) sessions.put(key(assistant), session);
            }
        } catch (Exception ex) {
            sessions.clear();
            log.log(Level.WARNING, "AI 会话读取失败，已忽略该文件: " + ex);
        }
    }

    /** 启动周期性异步保存（有改动才写盘）。 */
    public void start(JavaPlugin plugin) {
        long ticks = 30L * 20L;
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::saveIfDirty, ticks, ticks);
    }

    /** 关服时同步写盘，保证会话不丢。 */
    public synchronized void close() { save(); }

    public synchronized void saveIfDirty() { save(); }

    /**
     * 取该助手的历史上下文（含请求尚未返回的轮次之外的全部已完成问答），
     * 按时间从旧到新返回，且不超过 messages / chars 上限。
     */
    public List<AiClient.Msg> context(String assistant, Limits limits) {
        return context(assistant, limits, System.currentTimeMillis());
    }

    /** 供测试指定当前时间的重载。 */
    synchronized List<AiClient.Msg> context(String assistant, Limits limits, long now) {
        if (!limits.enabled()) return List.of();
        Session session = sessions.get(key(assistant));
        if (session == null) return List.of();
        expire(session, limits, now);
        trim(session, limits);
        List<AiClient.Msg> newestFirst = new ArrayList<>();
        int usedMessages = 0;
        int usedChars = 0;
        java.util.Iterator<Turn> newest = session.turns.descendingIterator();
        while (newest.hasNext()) {
            Turn turn = newest.next();
            if (turn.answer == null) continue;
            int size = turn.question.length() + turn.answer.length();
            if (usedMessages + 2 > limits.messages()) break;
            if (!limits.unlimitedChars() && usedChars + size > limits.chars()) break;
            usedMessages += 2;
            usedChars += size;
            newestFirst.add(new AiClient.Msg("assistant", turn.answer));
            newestFirst.add(new AiClient.Msg("user", turn.question));
        }
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    /** 开一轮新的问答；answer 由 {@link #finish} 补上，失败则用 {@link #fail} 丢弃。 */
    public synchronized Turn open(String assistant, Limits limits, String question) {
        String name = key(assistant);
        Session session = sessions.get(name);
        if (session == null) {
            session = new Session();
            sessions.put(name, session);
            evict();
        } else {
            expire(session, limits, System.currentTimeMillis());
        }
        session.lastActivity = System.currentTimeMillis();
        Turn turn = new Turn(name, question, null);
        session.turns.addLast(turn);
        trim(session, limits);
        dirty = true;
        return turn;
    }

    /** 请求成功：把回答补进会话并按上限裁剪。 */
    public synchronized void finish(Turn turn, String answer, Limits limits) {
        if (turn == null || answer == null) return;
        Session session = sessions.get(turn.assistant);
        if (session == null || !session.turns.contains(turn)) return;
        turn.answer = answer.length() > MAX_STORED_ANSWER ? answer.substring(0, MAX_STORED_ANSWER) : answer;
        session.lastActivity = System.currentTimeMillis();
        trim(session, limits);
        dirty = true;
    }

    /** 请求失败：这轮问答整个丢掉，不留半截上下文。 */
    public synchronized void fail(Turn turn) {
        if (turn == null) return;
        Session session = sessions.get(turn.assistant);
        if (session != null && session.turns.remove(turn)) dirty = true;
    }

    /** 会话数量护栏：超过上限时丢最久没活动的那个。 */
    private void evict() {
        while (sessions.size() > MAX_SESSIONS) {
            String oldest = null;
            long oldestTime = Long.MAX_VALUE;
            for (Map.Entry<String, Session> entry : sessions.entrySet()) {
                if (entry.getValue().lastActivity < oldestTime) {
                    oldestTime = entry.getValue().lastActivity;
                    oldest = entry.getKey();
                }
            }
            if (oldest == null) return;
            sessions.remove(oldest);
            dirty = true;
        }
    }

    private void expire(Session session, Limits limits, long now) {
        if (limits.neverExpires()) return;
        if (now - session.lastActivity <= limits.ttlSeconds() * 1000L) return;
        if (!session.turns.isEmpty()) {
            session.turns.clear();
            dirty = true;
        }
    }

    /** 按消息数与字符预算从最旧开始丢弃，未完成的轮次不计入也不被丢。 */
    private void trim(Session session, Limits limits) {
        if (!limits.enabled()) {
            if (!session.turns.isEmpty()) {
                session.turns.clear();
                dirty = true;
            }
            return;
        }
        while (true) {
            int messages = 0;
            int chars = 0;
            for (Turn turn : session.turns) {
                if (turn.answer == null) continue;
                messages += 2;
                chars += turn.question.length() + turn.answer.length();
            }
            boolean overMessages = messages > limits.messages();
            boolean overChars = !limits.unlimitedChars() && chars > limits.chars();
            if (!overMessages && !overChars) return;
            Turn victim = null;
            for (Turn turn : session.turns) {
                if (turn.answer != null) {
                    victim = turn;
                    break;
                }
            }
            if (victim == null) return;
            session.turns.remove(victim);
            dirty = true;
        }
    }

    private void save() {
        if (!persist || file == null || !dirty) return;
        try {
            JsonObject root = new JsonObject();
            JsonObject data = new JsonObject();
            for (Map.Entry<String, Session> entry : sessions.entrySet()) {
                JsonArray turns = new JsonArray();
                for (Turn turn : entry.getValue().turns) {
                    if (turn.answer == null) continue;
                    JsonObject value = new JsonObject();
                    value.addProperty("q", turn.question);
                    value.addProperty("a", turn.answer);
                    turns.add(value);
                }
                if (turns.isEmpty()) continue;
                JsonObject session = new JsonObject();
                session.addProperty("activity", entry.getValue().lastActivity);
                session.add("turns", turns);
                data.add(entry.getKey(), session);
            }
            root.addProperty("version", 1);
            root.add("sessions", data);
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (Exception ex) {
            log.log(Level.WARNING, "AI 会话保存失败: " + ex);
        }
    }

    private static String text(JsonObject turn, String field) {
        JsonElement value = turn.get(field);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return null;
        String text = value.getAsString();
        return text.isBlank() ? null : text;
    }

    private static String key(String assistant) {
        String value = assistant == null ? "" : assistant.strip();
        return value.length() <= MAX_ASSISTANT_KEY ? value : value.substring(0, MAX_ASSISTANT_KEY);
    }
}

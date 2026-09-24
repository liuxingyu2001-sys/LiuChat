package com.liu.liuchat.service;

import com.liu.liuchat.config.ConfigManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/** Appends daily chat files off the server thread, preserving message order. */
public final class ChatLogService implements AutoCloseable {
    /** 传统颜色/样式码（含 §x§RR§GG§BB 十六进制序列，逐对移除）。 */
    private static final String LEGACY_CODE = "(?i)§[0-9A-FK-ORX]";
    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "LiuChat-log-writer");
        thread.setDaemon(true);
        return thread;
    });

    public ChatLogService(JavaPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void local(String type, String player, String target, String message) {
        record(type, config.server(), player, target, message);
    }

    public void record(String type, String server, String player, String target, String message) {
        if (!config.logEnabled()) return;
        String format = config.logFormat();
        LocalDateTime time = LocalDateTime.now();
        String line = format.replace("${time}", time.format(DateTimeFormatter.ofPattern("HH:mm:ss")))
                .replace("${type}", clean(type)).replace("${server}", clean(server))
                .replace("${player}", clean(player)).replace("${target}", clean(target))
                .replace("${message}", clean(message));
        Path path = plugin.getDataFolder().toPath().resolve("logs")
                .resolve(time.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".log");
        writer.execute(() -> {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "聊天记录写入失败", e);
            }
        });
    }

    /** 去掉换行与颜色码，日志只保留可读纯文本（不含玩家字面输入的 & 符号）。 */
    static String clean(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\r\\n]+", " ").replaceAll(LEGACY_CODE, "");
    }

    public void recordPublic(String uuid, String player, String message) {
        if (!config.aiEnabled()) return;
        Instant time = Instant.now();
        JsonObject json = new JsonObject();
        json.addProperty("uuid", uuid);
        json.addProperty("player", player);
        json.addProperty("time", time.toString());
        String clean = message.replace('\r', ' ').replace('\n', ' ');
        json.addProperty("message", clean.substring(0, Math.min(300, clean.length())));
        Path path = plugin.getDataFolder().toPath().resolve("audit-history")
                .resolve(LocalDate.ofInstant(time, ZoneId.systemDefault()) + ".jsonl");
        writer.execute(() -> {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, json + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "审核历史写入失败", e);
            }
        });
    }

    public void recordPrivate(String uuid, String player, String target, String message) {
        if (!config.aiEnabled() || !config.aiReviewPrivate()) return;
        recordPublic(uuid, player, "[私聊 -> " + clean(target) + "] " + message);
    }
    public java.util.concurrent.CompletableFuture<HourlyChatHistory.Snapshot> recentMinutes(int minutes, Instant now) {
        var result = new java.util.concurrent.CompletableFuture<HourlyChatHistory.Snapshot>();
        writer.execute(() -> {
            try {
                result.complete(readHistory(plugin.getDataFolder().toPath().resolve("audit-history"), minutes, now));
            } catch (Exception e) { result.completeExceptionally(e); }
        });
        return result;
    }

    static HourlyChatHistory.Snapshot readHistory(Path directory, int minutes, Instant now) throws java.io.IOException {
        HourlyChatHistory history = new HourlyChatHistory();
        Instant since = now.minus(minutes, ChronoUnit.MINUTES);
        LocalDate day = LocalDate.ofInstant(since, ZoneId.systemDefault());
        LocalDate last = LocalDate.ofInstant(now, ZoneId.systemDefault());
        while (!day.isAfter(last)) {
            Path file = directory.resolve(day + ".jsonl");
            if (Files.isRegularFile(file)) {
                if (Files.size(file) > 32L * 1024 * 1024) throw new java.io.IOException("审核历史文件超过 32 MiB: " + file);
                try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
                    for (String line : (Iterable<String>) lines::iterator) {
                        try {
                            JsonObject json = JsonParser.parseString(line).getAsJsonObject();
                            Instant time = Instant.parse(json.get("time").getAsString());
                            if (!time.isBefore(since) && !time.isAfter(now))
                                history.add(json.get("uuid").getAsString(), json.get("player").getAsString(),
                                        json.get("message").getAsString(), time);
                        } catch (RuntimeException malformed) {
                            // Ignore a partially written or damaged line; keep the rest of the file.
                        }
                    }
                }
            }
            day = day.plusDays(1);
        }
        return history.drain();
    }

    @Override public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS))
                plugin.getLogger().warning("聊天记录仍在写入，停服时可能有未落盘内容");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}

package com.liu.liuchat.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.stream.Stream;

/** Resolves and watches LiuChat's optional shared configuration directory. */
public final class SharedConfig implements AutoCloseable {
    private final JavaPlugin plugin;
    private final Path root;
    private final Logger logger;
    private volatile Map<String, FileStamp> baseline = Map.of();
    static final List<String> CONFIG_FILES = List.of("config.yml", "messages.yml",
            "shortcut.yml", "dialogs.yml", "npc-assistants.yml", "reminders.yml");
    private volatile BukkitTask task;
    private volatile boolean closed;
    private final AtomicBoolean reloading = new AtomicBoolean();

    public SharedConfig(JavaPlugin plugin, Path root) {
        this.plugin = Objects.requireNonNull(plugin);
        this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
        this.logger = plugin.getLogger();
    }

    public static Path resolveRoot(Path localRoot, String configured, Path automatic, Logger logger) {
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured).toAbsolutePath().normalize();
            try {
                Files.createDirectories(path);
                logger.info("使用共享配置目录: " + path);
                return path;
            } catch (IOException e) {
                logger.warning("无法创建共享配置目录 " + path + "，回退插件目录: " + e.getMessage());
                return localRoot.toAbsolutePath().normalize();
            }
        }
        Path detected = automatic.toAbsolutePath().normalize();
        if (Files.isDirectory(detected)) {
            logger.info("使用自动检测的共享配置目录: " + detected);
            return detected;
        }
        return localRoot.toAbsolutePath().normalize();
    }

    public void validate() {
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("配置目录不存在: " + root);
        }
        for (String name : CONFIG_FILES) {
            Path file = root.resolve(name);
            if (!Files.exists(file)) continue;
            try {
                new YamlConfiguration().load(file.toFile());
            } catch (IOException | InvalidConfigurationException e) {
                throw new IllegalStateException("Invalid configuration: " + file, e);
            }
        }
        Path localChat = plugin.getDataFolder().toPath().resolve("chat.yml");
        try {
            if (Files.exists(localChat)) new YamlConfiguration().load(localChat.toFile());
        } catch (IOException | InvalidConfigurationException e) {
            throw new IllegalStateException("Invalid local configuration: " + localChat, e);
        }
        Path local = plugin.getDataFolder().toPath().toAbsolutePath().normalize().resolve("config.yml");
        if (!local.equals(root.resolve("config.yml"))) {
            try {
                new YamlConfiguration().load(local.toFile());
            } catch (IOException | InvalidConfigurationException e) {
                throw new IllegalStateException("Invalid local configuration: " + local, e);
            }
        }
    }

    public synchronized void watch(boolean enabled, Runnable reload) {
        if (closed) return;
        cancelTask();
        if (!enabled) {
            baseline = Map.of();
            return;
        }
        baseline = snapshot();
        task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (closed) return;
            Map<String, FileStamp> current;
            try {
                current = snapshot();
            } catch (IllegalStateException e) {
                logger.warning("扫描配置目录失败: " + e.getMessage());
                return;
            }
            Map<String, FileStamp> previous = baseline;
            if (!current.equals(previous)) {
                baseline = current;
                if (reloading.compareAndSet(false, true)) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        try {
                            if (closed || !plugin.isEnabled()) return;
                            reload.run();
                            logger.info("共享配置已自动重载");
                        } catch (RuntimeException e) {
                            logger.log(java.util.logging.Level.WARNING, "共享配置自动重载失败", e);
                        } finally {
                            reloading.set(false);
                        }
                    });
                }
            }
        }, 200L, 200L);
    }

    private Map<String, FileStamp> snapshot() {
        try {
            Map<String, FileStamp> result = new HashMap<>(snapshot(root,
                    plugin instanceof com.liu.liuchat.LiuChat chat
                            ? chat.getSkillsRoot() : root.resolve("skills")));
            Path localChat = plugin.getDataFolder().toPath().resolve("chat.yml");
            if (Files.isRegularFile(localChat)) {
                result.put("local/chat.yml", new FileStamp(Files.getLastModifiedTime(localChat).toMillis(),
                        Files.size(localChat)));
            }
            return Map.copyOf(result);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot scan configuration: " + root, e);
        }
    }

    static Map<String, FileStamp> snapshot(Path root) throws IOException {
        return snapshot(root, root.resolve("skills"));
    }

    static Map<String, FileStamp> snapshot(Path root, Path skills) throws IOException {
        Map<String, FileStamp> result = new HashMap<>();
        for (String name : CONFIG_FILES) {
            Path file = root.resolve(name);
            if (Files.isRegularFile(file)) record(result, root, file);
        }
        if (Files.isDirectory(skills)) {
            try (Stream<Path> paths = Files.walk(skills, 6)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                    if (name.endsWith(".md") || name.endsWith(".txt")) {
                        result.put("skills/" + skills.relativize(path),
                                new FileStamp(Files.getLastModifiedTime(path).toMillis(), Files.size(path)));
                    }
                }
            }
        }
        return Map.copyOf(result);
    }

    private static void record(Map<String, FileStamp> result, Path root, Path path) throws IOException {
        result.put(root.relativize(path).toString(),
                new FileStamp(Files.getLastModifiedTime(path).toMillis(), Files.size(path)));
    }

    private synchronized void cancelTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    @Override
    public void close() {
        closed = true;
        cancelTask();
    }

    record FileStamp(long modified, long size) { }
}

package com.liu.liuchat.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Bounded, read-only snapshots of administrator-provided assistant skill folders. */
public final class AiSkillService {
    private static final int MAX_FILES = 32;
    private static final int MAX_FILE_BYTES = 24576;
    private static final int MAX_TOTAL_CHARS = 32000;
    private static final int MAX_DEPTH = 5;
    private volatile Map<String, String> skills = Map.of();
    private final Path root;

    public AiSkillService(Path root) {
        this.root = root;
    }

    public void reload() throws IOException {
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) throw new IOException("Skill directory cannot be a symlink");
        Map<String, String> loaded = new LinkedHashMap<>();
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.sorted().toList()) {
                String name = dir.getFileName().toString();
                if (!name.matches("[a-zA-Z0-9_-]{1,48}") || Files.isSymbolicLink(dir)
                        || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) continue;
                String content = load(dir);
                if (!content.isBlank()) loaded.put(name, content);
            }
        }
        skills = Map.copyOf(loaded);
    }

    private static String load(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(dir, MAX_DEPTH)) {
            for (Path path : paths.toList()) {
                if (Files.isSymbolicLink(path)) continue;
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                String file = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                if (file.endsWith(".md") || file.endsWith(".txt")) files.add(path);
            }
        }
        files.sort(Comparator.comparing((Path p) -> !p.getFileName().toString().equalsIgnoreCase("SKILL.md"))
                .thenComparing(p -> dir.relativize(p).toString()));
        StringBuilder result = new StringBuilder();
        for (Path file : files.subList(0, Math.min(files.size(), MAX_FILES))) {
            long size = Files.size(file);
            if (size > MAX_FILE_BYTES) continue;
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String relative = dir.relativize(file).toString().replace('\\', '/');
            if (result.length() + relative.length() + text.length() + 12 > MAX_TOTAL_CHARS) break;
            result.append("\n\n## ").append(relative).append("\n").append(text);
        }
        return result.toString().strip();
    }

    public List<String> names() { return skills.keySet().stream().sorted().toList(); }
    public String content(String name) { return skills.get(name); }
}

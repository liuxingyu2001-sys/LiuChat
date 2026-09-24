package com.liu.liuchat.service;

import org.bukkit.Material;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Bundled Chinese names for vanilla item IDs. */
final class VanillaItemNames {
    private final Map<String, String> names;

    VanillaItemNames() {
        Map<String, String> loaded = new HashMap<>();
        try (InputStream input = VanillaItemNames.class.getResourceAsStream("/material-zh.map")) {
            if (input != null) {
                try (var lines = new java.io.BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                    lines.lines().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#"))
                            .forEach(line -> {
                                int split = line.indexOf('=');
                                if (split > 0) loaded.put(line.substring(0, split).strip(), line.substring(split + 1).strip());
                            });
                }
            }
        } catch (Exception ignored) { }
        names = Map.copyOf(loaded);
    }

    String resolve(Material material) {
        String key = material.name();
        String name = names.get(key);
        return name == null ? material.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') : name;
    }
}

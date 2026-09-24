package com.liu.liuchat.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Resolves CraftEngine resource-pack <lang:key> item names for server-side chat labels. */
public final class CraftEngineNames {
    private static final Pattern LANG = Pattern.compile("<lang:([^>]+)>");
    private static final Pattern KEY = Pattern.compile("(?:item|block)\\.[a-z0-9_.:-]+");
    private Map<String, String> translations = Map.of();

    CraftEngineNames(Map<String, String> translations) {
        this.translations = Map.copyOf(translations);
    }

    public CraftEngineNames() { }

    public void reload() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("CraftEngine");
        if (plugin == null) { translations = Map.of(); return; }
        File resources = new File(plugin.getDataFolder(), "resources");
        Map<String, String> values = new HashMap<>();
        if (!resources.isDirectory()) return;
        try (Stream<java.nio.file.Path> files = Files.walk(resources.toPath(), 8)) {
            files.filter(f -> f.getFileName().toString().equalsIgnoreCase("zh_cn.yml"))
                    .limit(128).forEach(f -> values.putAll(readFile(f.toFile())));
        } catch (IOException ignored) { }
        translations = Map.copyOf(values);
    }

    static Map<String, String> readFile(File file) {
        Map<String, String> values = new HashMap<>();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String root : yaml.getKeys(false)) {
            if (!root.startsWith("lang#") && !root.startsWith("translations#")) continue;
            var section = yaml.getConfigurationSection(root + ".zh_cn");
            if (section == null) continue;
            for (var entry : section.getValues(true).entrySet()) {
                if (entry.getValue() instanceof String text) values.put(entry.getKey(), text);
            }
        }
        return values;
    }

    public String resolve(Component component) {
        if (component == null) return null;
        return PlainTextComponentSerializer.plainText().serialize(resolveComponent(component));
    }

    private Component resolveComponent(Component component) {
        List<Component> children = component.children().stream().map(this::resolveComponent).toList();
        if (component instanceof TranslatableComponent translatable) {
            String translation = lookup(translatable.key());
            if (translation != null) return Component.text(resolve(translation)).children(children);
        }
        Component resolved = component.children(children);
        if (resolved instanceof net.kyori.adventure.text.TextComponent text) {
            resolved = text.content(resolve(text.content()));
        }
        return resolved;
    }

    private String lookup(String key) {
        String result = translations.get(key);
        if (result == null && key.contains(":")) result = translations.get(key.replace(':', '.'));
        if (result == null && key.startsWith("item.") && key.contains(":")) {
            result = translations.get("item.default." + key.substring(key.indexOf(':') + 1));
        }
        return result;
    }

    public String resolve(String name) {
        if (name == null) return null;
        Matcher match = LANG.matcher(name);
        StringBuffer result = new StringBuffer();
        while (match.find()) {
            String key = match.group(1);
            match.appendReplacement(result, Matcher.quoteReplacement(
                    lookup(key) == null ? key : lookup(key)));
        }
        match.appendTail(result);
        String plain = result.toString();
        Matcher key = KEY.matcher(plain);
        StringBuffer expanded = new StringBuffer();
        while (key.find()) {
            String translation = lookup(key.group());
            key.appendReplacement(expanded, Matcher.quoteReplacement(
                    translation == null ? key.group() : translation));
        }
        key.appendTail(expanded);
        return expanded.toString();
    }
}

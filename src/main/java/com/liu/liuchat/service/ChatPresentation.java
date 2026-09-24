package com.liu.liuchat.service;

import com.liu.liuchat.config.ConfigManager;
import com.liu.liuchat.config.ConfigDefaults;
import com.liu.liuchat.hook.PapiHook;
import com.liu.liuchat.util.TextUtil;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Item;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Ordered chat nodes and message shortcuts from configuration. */
public final class ChatPresentation {
    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final ItemShowcase items;
    private YamlConfiguration chat;
    private List<Shortcut> shortcuts = List.of();
    private final ThreadLocal<Map<String, String>> remoteValues = ThreadLocal.withInitial(Map::of);
    private static final Pattern PAPI_TOKEN = Pattern.compile("%[^%\\r\\n]{1,100}%");
    private final ThreadLocal<String> displayNick = ThreadLocal.withInitial(() -> "");

    private record Shortcut(Pattern pattern, Pattern filter, String text, List<String> hover,
                            String click, String suggest, String url) { }

    public ChatPresentation(JavaPlugin plugin, ConfigManager config, ItemShowcase items) {
        this.plugin = plugin;
        this.config = config;
        this.items = items;
        reload();
    }

    public void reload() {
        items.reloadTranslations();
        chat = ConfigDefaults.load(plugin, "chat.yml");
        YamlConfiguration rules = ConfigDefaults.load(plugin, "shortcut.yml");
        List<Shortcut> loaded = new ArrayList<>();
        if (rules.getBoolean("enable", false)) {
            for (String key : rules.getKeys(false)) {
                ConfigurationSection section = rules.getConfigurationSection(key);
                if (section == null || !section.contains("pattern")) continue;
                try {
                    loaded.add(new Shortcut(Pattern.compile(section.getString("pattern")),
                            section.contains("text-filter") ? Pattern.compile(section.getString("text-filter")) : null,
                            section.getString("display.text", ""), section.getStringList("display.hover"),
                            section.getString("display.click", ""), section.getString("display.clickSuggest", ""),
                            section.getString("display.url", "")));
                } catch (PatternSyntaxException ex) {
                    plugin.getLogger().warning("无效的快捷触发正则: " + key + ": " + ex.getMessage());
                }
            }
        }
        shortcuts = List.copyOf(loaded);
    }

    public String snapshotPlaceholders(Player player) {
        if (player == null) return "";
        String source = chat.saveToString() + config.format() + config.consoleFormat();
        Matcher matcher = PAPI_TOKEN.matcher(source);
        Map<String, String> values = new LinkedHashMap<>();
        while (matcher.find() && values.size() < 100) {
            String token = matcher.group();
            if (!values.containsKey(token)) values.put(token, PapiHook.setPlaceholders(player, token));
        }
        YamlConfiguration yaml = new YamlConfiguration();
        for (var entry : values.entrySet()) {
            String key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            yaml.set("values." + key, entry.getValue());
        }
        String serialized = yaml.saveToString();
        return serialized.length() <= 8000 ? serialized : "";
    }

    public boolean itemEnabled() { return chat.getBoolean("item.enable", true); }
    public String itemToken() {
        String token = chat.getString("item.format", "[i]");
        return token == null || token.isEmpty() ? "[i]" : token;
    }

    public BaseComponent[] render(String server, String playerName, String uuid, String world,
                                  Player sender, String message, String itemId, Player viewer,
                                  String resolved, String nick) {
        Map<String, String> values = new LinkedHashMap<>();
        if (sender == null && resolved != null && !resolved.isEmpty() && resolved.length() <= 8000) {
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.loadFromString(resolved);
                ConfigurationSection section = yaml.getConfigurationSection("values");
                if (section != null) for (String key : section.getKeys(false)) {
                    try {
                        String token = new String(java.util.Base64.getUrlDecoder().decode(key),
                                java.nio.charset.StandardCharsets.UTF_8);
                        if (PAPI_TOKEN.matcher(token).matches()) values.put(token, section.getString(key, ""));
                    } catch (IllegalArgumentException ignored) { }
                }
            } catch (Exception ignored) { }
        }
        remoteValues.set(values);
        displayNick.set(nick);
        try {
            return renderLine(server, playerName, uuid, world, sender, message, itemId, viewer);
        } finally {
            remoteValues.remove();
            displayNick.remove();
        }
    }

    private BaseComponent[] renderLine(String server, String playerName, String uuid, String world,
                                  Player sender, String message, String itemId, Player viewer) {
        boolean mentioned = viewer != null && chat.getBoolean("at.enable", false)
                && Pattern.compile("(?i)@" + Pattern.quote(viewer.getName()) + "(?![A-Za-z0-9_])")
                        .matcher(message).find();
        if (mentioned) {
            String highlight = TextUtil.color(chat.getString("at.atColor", "&b"));
            String replacement = highlight + (chat.getBoolean("at.keepAt", true) ? "@" : "")
                    + viewer.getName() + "§r";
            message = message.replaceAll("(?i)@" + Pattern.quote(viewer.getName()) + "(?![A-Za-z0-9_])",
                    Matcher.quoteReplacement(replacement));
            try {
                viewer.playSound(viewer.getLocation(), Sound.valueOf(chat.getString("at.sound", "BLOCK_ANVIL_LAND")), 1, 1);
            } catch (IllegalArgumentException ex) {
                // Ignore invalid sound names in the configuration.
            }
        }
        TextComponent line = new TextComponent();
        ConfigurationSection nodes = chat.getConfigurationSection("chat.default.format");
        if (nodes == null || !chat.getBoolean("chat.default.enable", true)) {
            String fallback = template(config.format(), server, playerName, world, sender);
            int position = fallback.indexOf("${message}");
            if (position < 0) append(line, fallback, null, null);
            else {
                append(line, fallback.substring(0, position), null, null);
                appendMessage(line, message, server, playerName, world, sender, itemId);
                append(line, fallback.substring(position + "${message}".length()), null, null);
            }
        } else {
            for (String key : nodes.getKeys(false)) {
                ConfigurationSection node = nodes.getConfigurationSection(key);
                if (node == null) continue;
                String text = node.getString("text", "");
                String hover = String.join("\n", node.getStringList("hover"));
                String click = template(node.getString("click", ""), server, playerName, world, sender);
                String suggest = template(node.getString("clickSuggest", ""), server, playerName, world, sender);
                String url = template(node.getString("url", ""), server, playerName, world, sender);
                ClickEvent action = action(click, suggest, url);
                HoverEvent hint = hover.isEmpty() ? null : hover(template(hover, server, playerName, world, sender));
                int position = text.indexOf("${message}");
                if (position < 0) {
                    append(line, template(text, server, playerName, world, sender), hint, action, uuid);
                } else {
                    append(line, template(text.substring(0, position), server, playerName, world, sender), hint, action, uuid);
                    appendMessage(line, message, server, playerName, world, sender, itemId, hint, action);
                    append(line, template(text.substring(position + "${message}".length()), server, playerName, world, sender), hint, action, uuid);
                }
            }
        }
        return line.getExtra() == null ? new BaseComponent[0] : line.getExtra().toArray(BaseComponent[]::new);
    }

    private void appendMessage(TextComponent line, String message, String server, String player,
                               String world, Player sender, String itemId) {
        appendMessage(line, message, server, player, world, sender, itemId, null, null);
    }

    private void appendMessage(TextComponent line, String message, String server, String player,
                               String world, Player sender, String itemId, HoverEvent hint, ClickEvent action) {
        String marker = itemId == null ? "" : itemToken();
        int offset = 0;
        while (!marker.isEmpty()) {
            int index = message.indexOf(marker, offset);
            if (index < 0) break;
            shortcuts(line, message.substring(offset, index), server, player, world, sender, hint, action);
            ItemStack stack = items.item(itemId);
            if (stack != null) {
                String name = items.name(itemId, Math.max(1, chat.getInt("item.length", 18)));
                String shown = chat.getString("item.content", "&e[ ${item} &e]").replace("${item}", name);
                HoverEvent itemHover = new HoverEvent(HoverEvent.Action.SHOW_ITEM,
                        new Item(stack.getType().getKey().toString(), stack.getAmount(), null));
                append(line, TextUtil.color(shown), itemHover,
                        new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/lc item " + itemId));
            }
            offset = index + marker.length();
        }
        shortcuts(line, message.substring(offset), server, player, world, sender, hint, action);
    }

    private void shortcuts(TextComponent line, String message, String server, String player, String world,
                           Player sender, HoverEvent hint, ClickEvent action) {
        int offset = 0;
        int count = 0;
        while (count < 20) {
            Shortcut found = null;
            Matcher match = null;
            for (Shortcut shortcut : shortcuts) {
                Matcher candidate = shortcut.pattern.matcher(message);
                if (candidate.find(offset) && candidate.start() != candidate.end()
                        && (match == null || candidate.start() < match.start())) {
                    found = shortcut;
                    match = candidate;
                }
            }
            if (found == null) break;
            append(line, message.substring(offset, match.start()), hint, action);
            String[] groups = new String[10];
            groups[0] = match.group();
            Matcher filtered = found.filter == null ? null : found.filter.matcher(match.group());
            if (filtered != null && filtered.find()) {
                for (int i = 0; i < groups.length && i <= filtered.groupCount(); i++) groups[i] = filtered.group(i);
            }
            String text = expand(found.text, groups, server, player, world, sender);
            String hoverText = expand(String.join("\n", found.hover), groups, server, player, world, sender);
            ClickEvent replacement = action(expand(found.click, groups, server, player, world, sender),
                    expand(found.suggest, groups, server, player, world, sender),
                    expand(found.url, groups, server, player, world, sender));
            append(line, TextUtil.color(text), hoverText.isEmpty() ? hint : hover(hoverText),
                    replacement == null ? action : replacement);
            offset = match.end();
            count++;
        }
        append(line, message.substring(offset), hint, action);
    }

    private String expand(String text, String[] groups, String server, String player, String world, Player sender) {
        String result = template(text, server, player, world, sender);
        for (int i = 0; i < groups.length; i++) result = result.replace("{" + i + "}", groups[i] == null ? "" : groups[i]);
        return result;
    }

    private String template(String text, String server, String player, String world, Player sender) {
        String result = text.replace("${server}", server).replace("${player}", player)
                .replace("${nick}", displayNick.get()).replace("${world}", world);
        if (sender != null) result = PapiHook.setPlaceholders(sender, result);
        else for (var entry : remoteValues.get().entrySet()) result = result.replace(entry.getKey(), entry.getValue());
        return TextUtil.color(result);
    }

    private static HoverEvent hover(String text) {
        return new HoverEvent(HoverEvent.Action.SHOW_TEXT, TextComponent.fromLegacyText(TextUtil.color(text)));
    }

    private static ClickEvent action(String click, String suggest, String url) {
        if (!click.isEmpty()) return new ClickEvent(click.startsWith("/")
                ? ClickEvent.Action.RUN_COMMAND : ClickEvent.Action.COPY_TO_CLIPBOARD, click);
        if (!suggest.isEmpty()) return new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, suggest);
        if (url.startsWith("https://") || url.startsWith("http://"))
            return new ClickEvent(ClickEvent.Action.OPEN_URL, url);
        return null;
    }

    private static void append(TextComponent line, String text, HoverEvent hover, ClickEvent click, String uuid) {
        int pos = 0;
        int index;
        while ((index = text.indexOf("${head}", pos)) >= 0) {
            append(line, text.substring(pos, index), hover, click);
            try {
                TextComponent head = new TextComponent("");
                head.setInsertion("liuchat-head:" + java.util.UUID.fromString(uuid));
                if (hover != null) head.setHoverEvent(hover);
                if (click != null) head.setClickEvent(click);
                line.addExtra(head);
            } catch (IllegalArgumentException ignored) { }
            pos = index + "${head}".length();
        }
        append(line, text.substring(pos), hover, click);
    }

    private static void append(TextComponent line, String text, HoverEvent hover, ClickEvent click) {
        if (text.isEmpty()) return;
        TextComponent segment = new TextComponent(TextComponent.fromLegacyText(text));
        if (hover != null) segment.setHoverEvent(hover);
        if (click != null) segment.setClickEvent(click);
        line.addExtra(segment);
    }
}

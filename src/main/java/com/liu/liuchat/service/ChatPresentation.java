package com.liu.liuchat.service;

import com.liu.liuchat.config.ConfigManager;
import com.liu.liuchat.config.ConfigDefaults;
import com.liu.liuchat.hook.CraftEngineEmojiHook;
import com.liu.liuchat.hook.NameplatesHook;
import com.liu.liuchat.hook.PapiHook;
import com.liu.liuchat.util.Mentions;
import com.liu.liuchat.util.TextUtil;
import com.liu.liuchat.util.TimePlaceholders;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Item;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Ordered chat nodes and message shortcuts from configuration. */
public final class ChatPresentation implements ItemShowcase.SpaceSettings {
    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final ItemShowcase items;
    private YamlConfiguration chat;
    /** 物品展示 token 解析器；item.enable 关闭时为 null（整条消息按普通文本处理）。 */
    private ItemTokens.Parser itemTokens;
    private List<Shortcut> shortcuts = List.of();
    private final ThreadLocal<Map<String, String>> remoteValues = ThreadLocal.withInitial(Map::of);
    private final ThreadLocal<Map<String, String>> emojiValues = ThreadLocal.withInitial(Map::of);
    private final ThreadLocal<YamlConfiguration> remoteFormat = new ThreadLocal<>();
    private static final Pattern PAPI_TOKEN = Pattern.compile("%[^%\\r\\n]{1,100}%");
    /** 物品数据拿不到时的占位（一个 token 一个占位，不会展开成多件）。 */
    private static final String ITEM_UNAVAILABLE = "§7[物品不可展示]§r";
    private static final Pattern CHAT_URL = Pattern.compile("(?i)(https?://[^\\s§<>\\\"{}|\\\\^`]+|www\\.[^\\s§<>\\\"{}|\\\\^`]+)");
    private final ThreadLocal<String> displayNick = ThreadLocal.withInitial(() -> "");
    private final ThreadLocal<String> privateTarget = ThreadLocal.withInitial(() -> "");
    /** 跨服在线玩家 ID（用于「输入玩家 ID 自动补 @」），由主类注入 */
    private Supplier<Collection<String>> knownNames = List::of;
    /** 同一条消息会逐个玩家渲染，@ 提及的标记结果按消息缓存，避免重复扫描 */
    private final Map<String, Mentions.Marked> mentionCache =
            new LinkedHashMap<>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Mentions.Marked> eldest) {
                    return size() > 8;
                }
            };

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
        itemTokens = itemEnabled()
                ? ItemTokens.parser(new ItemTokens.Settings(itemToken(), itemSlots(), itemArmor(), itemOffhand()))
                : null;
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
        synchronized (mentionCache) {
            mentionCache.clear();
        }
    }

    /** 注入跨服在线玩家名单（与本服在线玩家一起用于自动补 @）。 */
    public void setKnownNames(Supplier<Collection<String>> source) {
        this.knownNames = source == null ? List::of : source;
    }

    /**
     * 标记 @ 提及：把玩家 ID 高亮成「@玩家」，并返回被 @ 的名字。
     * 高亮在 liuchat.color 颜色权限裁决之后注入，没有颜色权限的玩家 @ 人同样会变色。
     */
    public Mentions.Marked markMentions(String message) {
        if (message == null || message.isEmpty() || !chat.getBoolean("at.enable", false)) {
            return new Mentions.Marked(message == null ? "" : message, List.of());
        }
        synchronized (mentionCache) {
            Mentions.Marked cached = mentionCache.get(message);
            if (cached != null) return cached;
            List<String> names = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) names.add(online.getName());
            Collection<String> extra = knownNames.get();
            if (extra != null) names.addAll(extra);
            Mentions.Marked marked = Mentions.mark(message, names, chat.getBoolean("at.keepAt", true),
                    mentionColor(chat.getString("at.atColor", "&b")), itemSpans(message));
            mentionCache.put(message, marked);
            return marked;
        }
    }

    /** 物品 token 区间：@ 提及不得染进 token，否则 token 正则失配、chip 渲染不出来还会串位。 */
    private List<int[]> itemSpans(String message) {
        return itemTokens == null ? List.of() : itemTokens.spans(message);
    }

    /** ColorParser discards a bare formatting code; give it text to color, then remove that text. */
    static String mentionColor(String configured) {
        if (configured == null || configured.isBlank()) return "";
        String sentinel = "\uE000";
        String colored = TextUtil.color(configured + sentinel);
        return colored.endsWith(sentinel) ? colored.substring(0, colored.length() - sentinel.length()) : "";
    }

    /** 给被 @ 的玩家播放提示音（at.sound，默认铁砧）。 */
    public void playMentionSound(Player viewer) {
        String name = chat.getString("at.sound", "BLOCK_ANVIL_LAND");
        if (name == null || name.isBlank()) return;
        try {
            viewer.playSound(viewer.getLocation(), Sound.valueOf(name.trim().toUpperCase(Locale.ROOT)), 1f, 1f);
        } catch (IllegalArgumentException ex) {
            // Ignore invalid sound names in the configuration.
        }
    }

    public String snapshotPlaceholders(Player player, String message) {
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
        for (var entry : CraftEngineEmojiHook.resolve(player, message).entrySet()) {
            String key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            yaml.set("emojis." + key, entry.getValue());
        }
        yaml.set("source-format.enabled", chat.getBoolean("chat.default.enable", true));
        yaml.set("source-format.legacy", config.format());
        yaml.set("source-format.nodes", chat.get("chat.default.format"));
        String serialized = yaml.saveToString();
        return serialized.length() <= 8000 ? serialized : "";
    }

    public boolean itemEnabled() { return chat.getBoolean("item.enable", true); }

    /** 主手 token 字面量（只管这一个，槽位/盔甲/副手 token 固定写法）。 */
    public String itemToken() {
        String token = chat.getString("item.format", "[i]");
        return token == null || token.isEmpty() ? "[i]" : token;
    }

    /** token 解析结果；物品展示关闭时整条消息就是一段普通文本。 */
    public List<ItemTokens.Part> parseItems(String message) {
        if (message == null || message.isEmpty()) return List.of();
        return itemTokens == null ? List.of(new ItemTokens.Plain(message)) : itemTokens.parse(message);
    }

    /** 消息里是否有可展示的物品 token（发送、渲染、重复检测共用）。 */
    public boolean hasItemToken(String message) {
        return itemTokens != null && itemTokens.hasToken(message);
    }

    /** 个人配色保护用：token 起始位置匹配。 */
    public ItemTokens.Parser itemTokens() { return itemTokens; }

    /** 展示不了时把每个 token 换成占位文本。 */
    public String itemUnavailable(String message) {
        if (message == null || itemTokens == null) return message;
        return itemTokens.replace(message, ITEM_UNAVAILABLE);
    }

    /** 同一条消息里相邻两个展示 chip 之间插入的内容。 */
    public String itemSeparator() { return chat.getString("item.separator", "&7, "); }

    /** 展示件数达到 2 件时改用的紧凑样式；留空则一律用 content。 */
    public String itemMultiContent() { return chat.getString("item.multi-content", "&e[ ${item} ]"); }

    /** 单条消息最多展示几件（9 快捷栏 + 4 盔甲 + 1 副手 = 14）。 */
    public int itemMaxCount() { return Math.max(1, chat.getInt("item.max-count", 14)); }

    private boolean itemSlots() { return chat.getBoolean("item.slots", true); }
    private boolean itemArmor() { return chat.getBoolean("item.armor", true); }
    private boolean itemOffhand() { return chat.getBoolean("item.offhand", true); }

    public boolean privateEnabled() { return chat.getBoolean("private.enable", true); }

    @Override public boolean spacePreviewEnabled() { return chat.getBoolean("item.soulspace.enable", true); }
    @Override public String spaceRingKey() {
        String key = chat.getString("item.soulspace.ring-pdc-key", "soulspace:ring");
        return key == null || key.isBlank() ? "soulspace:ring" : key;
    }
    @Override public String spacePreviewPermission() {
        String perm = chat.getString("item.soulspace.preview-permission", "liuchat.soulspace.preview");
        return perm == null || perm.isBlank() ? "liuchat.soulspace.preview" : perm;
    }
    @Override public String spaceSort() { return chat.getString("item.soulspace.sort", "count-desc"); }

    public BaseComponent[] renderPrivate(boolean outgoing, String server, String playerName, String uuid,
                                         String world, String targetName, Player sender, String message,
                                         String itemId, String resolved, String nick) {
        privateTarget.set(targetName);
        try {
            return renderInternal(server, playerName, uuid, world, sender, message, itemId, null,
                    resolved, nick, "private." + (outgoing ? "to" : "from") + ".format");
        } finally {
            privateTarget.remove();
        }
    }

    public BaseComponent[] render(String server, String playerName, String uuid, String world,
                                  Player sender, String message, String itemId, Player viewer,
                                  String resolved, String nick) {
        return renderInternal(server, playerName, uuid, world, sender, message, itemId, viewer,
                resolved, nick, "chat.default.format");
    }

    private BaseComponent[] renderInternal(String server, String playerName, String uuid, String world,
                                          Player sender, String message, String itemId, Player viewer,
                                          String resolved, String nick, String formatPath) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> emojis = new LinkedHashMap<>();
        if (resolved != null && !resolved.isEmpty() && resolved.length() <= 8000) {
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.loadFromString(resolved);
                ConfigurationSection section = yaml.getConfigurationSection("values");
                if (section != null) for (String key : section.getKeys(false)) {
                    try {
                        String token = new String(java.util.Base64.getUrlDecoder().decode(key),
                                java.nio.charset.StandardCharsets.UTF_8);
                        if (sender == null && PAPI_TOKEN.matcher(token).matches())
                            values.put(token, section.getString(key, ""));
                    } catch (IllegalArgumentException ignored) { }
                }
                ConfigurationSection emojiSection = yaml.getConfigurationSection("emojis");
                if (emojiSection != null) for (String key : emojiSection.getKeys(false)) {
                    try {
                        String token = new String(java.util.Base64.getUrlDecoder().decode(key),
                                java.nio.charset.StandardCharsets.UTF_8);
                        if (!token.isEmpty() && token.length() <= 100 && emojis.size() < 16)
                            emojis.put(token, emojiSection.getString(key, ""));
                    } catch (IllegalArgumentException ignored) { }
                }
            } catch (Exception ignored) { }
        }
        remoteValues.set(values);
        emojiValues.set(emojis);
        ConfigurationSection sourceFormat = null;
        if (sender == null && resolved != null && !resolved.isEmpty() && resolved.length() <= 8000) {
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.loadFromString(resolved);
                if (yaml.isConfigurationSection("source-format")) {
                    remoteFormat.set(yaml);
                    sourceFormat = yaml.getConfigurationSection("source-format");
                }
            } catch (Exception ignored) { }
        }
        displayNick.set(nick);
        try {
            return renderLine(server, playerName, uuid, world, sender, message, itemId, viewer, formatPath,
                    sourceFormat);
        } finally {
            remoteValues.remove();
            emojiValues.remove();
            remoteFormat.remove();
            displayNick.remove();
        }
    }

    private BaseComponent[] renderLine(String server, String playerName, String uuid, String world,
                                  Player sender, String message, String itemId, Player viewer, String formatPath,
                                  ConfigurationSection sourceFormat) {
        Mentions.Marked marked = markMentions(message);
        message = marked.text();
        // 提示音只发给被 @ 的玩家，自己 @ 自己不响
        if (viewer != null && (sender == null || !sender.getUniqueId().equals(viewer.getUniqueId()))
                && marked.mentions(viewer.getName())) {
            playMentionSound(viewer);
        }
        TextComponent line = new TextComponent();
        YamlConfiguration source = remoteFormat.get();
        ConfigurationSection nodes = sourceFormat != null && source != null
                ? source.getConfigurationSection("source-format.nodes")
                : chat.getConfigurationSection(formatPath);
        boolean formatEnabled = sourceFormat != null
                ? sourceFormat.getBoolean("enabled", true)
                : chat.getBoolean("chat.default.enable", true);
        if (nodes == null || (formatPath.equals("chat.default.format") && !formatEnabled)) {
            String fallbackTemplate = sourceFormat != null && source != null
                    ? source.getString("source-format.legacy", config.format()) : config.format();
            String fallback = template(fallbackTemplate, server, playerName, world, sender);
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
                    String rendered = template(text, server, playerName, world, sender);
                    if (!appendImage(line, rendered, node, hint, action))
                        append(line, rendered, hint, action, uuid);
                } else {
                    append(line, template(text.substring(0, position), server, playerName, world, sender), hint, action, uuid);
                    appendMessage(line, message, server, playerName, world, sender, itemId, hint, action);
                    append(line, template(text.substring(position + "${message}".length()), server, playerName, world, sender), hint, action, uuid);
                }
            }
        }
        return line.getExtra() == null ? new BaseComponent[0] : line.getExtra().toArray(BaseComponent[]::new);
    }

    private static boolean appendImage(TextComponent line, String text, ConfigurationSection node,
                                       HoverEvent hint, ClickEvent action) {
        String kind = node.getString("image.type", "");
        String id = node.getString("image.id", "");
        if (kind.isEmpty() || id.isEmpty() || text.contains("${head}")
                || !org.bukkit.Bukkit.getPluginManager().isPluginEnabled("CustomNameplates")) return false;
        String mini = MiniMessage.miniMessage().serialize(LegacyComponentSerializer.legacySection().deserialize(text));
        String generated = NameplatesHook.withImage(mini, kind, id,
                (float) node.getDouble("image.left-margin", 1),
                (float) node.getDouble("image.right-margin", 1));
        if (generated == null) return false;
        TextComponent segment = new TextComponent("");
        segment.setInsertion("liuchat-image:" + java.util.Base64.getEncoder().encodeToString(
                generated.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (hint != null) segment.setHoverEvent(hint);
        if (action != null) segment.setClickEvent(action);
        line.addExtra(segment);
        return true;
    }

    private void appendMessage(TextComponent line, String message, String server, String player,
                               String world, Player sender, String itemId) {
        appendMessage(line, message, server, player, world, sender, itemId, null, null);
    }

    private void appendMessage(TextComponent line, String message, String server, String player,
                               String world, Player sender, String itemId, HoverEvent hint, ClickEvent action) {
        List<ItemTokens.Part> parts = parseItems(message);
        int[] counts = itemId == null ? null : items.counts(itemId);
        int total = 0;
        if (counts != null) for (int count : counts) total += count;
        // 单件仍用原来的「物品展示 [名]」，多件换紧凑样式，否则一条消息会重复 9 遍前缀
        String multi = itemMultiContent();
        String content = total >= 2 && !multi.isBlank()
                ? multi : chat.getString("item.content", "&e[ ${item} &e]");
        String separator = itemId == null ? "" : TextUtil.color(itemSeparator());
        int tokenIndex = 0;
        int stackIndex = 0;
        boolean chipShown = false;
        for (ItemTokens.Part part : parts) {
            if (part instanceof ItemTokens.Token token) {
                if (itemId == null) {
                    // 没有物品数据时保留原文，不吞掉玩家输入
                    shortcuts(line, token.raw(), server, player, world, sender, hint, action);
                    continue;
                }
                int count = counts == null || tokenIndex >= counts.length ? 0 : counts[tokenIndex];
                int base = stackIndex;
                tokenIndex++;
                stackIndex += count;
                for (int k = 0; k < count; k++) {
                    String id = ItemShowcase.subId(itemId, base + k);
                    ItemStack stack = items.item(id);
                    if (stack == null) continue;
                    if (chipShown) append(line, separator, hint, action);
                    String name = items.name(id, Math.max(1, chat.getInt("item.length", 18)));
                    String shown = content.replace("${item}", name);
                    HoverEvent itemHover = new HoverEvent(HoverEvent.Action.SHOW_ITEM,
                            new Item(stack.getType().getKey().toString(), stack.getAmount(), null));
                    append(line, TextUtil.color(shown), itemHover,
                            new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/liuc item " + id));
                    chipShown = true;
                }
                continue;
            }
            String text = ((ItemTokens.Plain) part).value();
            if (text.isEmpty()) continue;
            shortcuts(line, text, server, player, world, sender, hint, action);
            chipShown = false;
        }
    }

    private void shortcuts(TextComponent line, String message, String server, String player, String world,
                           Player sender, HoverEvent hint, ClickEvent action) {
        StringBuilder visible = new StringBuilder(message.length());
        int[] positions = new int[message.length() + 1];
        for (int i = 0; i < message.length();) {
            if (message.charAt(i) == '§' && i + 1 < message.length()) {
                i += 2;
                continue;
            }
            positions[visible.length()] = i;
            visible.append(message.charAt(i++));
        }
        int offset = 0;
        int rawOffset = 0;
        int count = 0;
        while (count < 20) {
            Shortcut found = null;
            Matcher match = null;
            for (Shortcut shortcut : shortcuts) {
                Matcher candidate = shortcut.pattern.matcher(visible);
                if (candidate.find(offset) && candidate.start() != candidate.end()
                        && (match == null || candidate.start() < match.start())) {
                    found = shortcut;
                    match = candidate;
                }
            }
            if (found == null) break;
            int rawStart = positions[match.start()];
            int rawEnd = positions[match.end() - 1] + 1;
            appendEmojis(line, message.substring(rawOffset, rawStart), emojiValues.get(), hint, action);
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
            rawOffset = rawEnd;
            offset = match.end();
            count++;
        }
        appendEmojis(line, message.substring(rawOffset), emojiValues.get(), hint, action);
    }

    static void appendEmojis(TextComponent line, String text, Map<String, String> emojis,
                             HoverEvent hint, ClickEvent action) {
        int offset = 0;
        for (int count = 0; count < 16; count++) {
            String matched = null;
            int index = -1;
            for (String keyword : emojis.keySet()) {
                int candidate = text.indexOf(keyword, offset);
                if (candidate >= 0 && (index < 0 || candidate < index
                        || candidate == index && keyword.length() > matched.length())) {
                    matched = keyword;
                    index = candidate;
                }
            }
            if (matched == null) break;
            appendAutoLinks(line, text.substring(offset, index), hint, action);
            TextComponent emoji = new TextComponent("");
            emoji.setColor(net.md_5.bungee.api.ChatColor.WHITE);
            emoji.setInsertion("liuchat-image:" + java.util.Base64.getEncoder().encodeToString(
                    emojis.get(matched).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            // CE content supplies its own hover; the message node must not mask it.
            if (action != null) emoji.setClickEvent(action);
            line.addExtra(emoji);
            offset = index + matched.length();
        }
        appendAutoLinks(line, text.substring(offset), hint, action);
    }

    static void appendAutoLinks(TextComponent line, String text, HoverEvent hint, ClickEvent inheritedClick) {
        Matcher matcher = CHAT_URL.matcher(text);
        int offset = 0;
        while (matcher.find()) {
            int end = matcher.end();
            while (end > matcher.start() && ".,!?;:)]}".indexOf(text.charAt(end - 1)) >= 0) end--;
            if (end == matcher.start()) continue;
            append(line, text.substring(offset, matcher.start()), hint, inheritedClick);
            String visibleUrl = text.substring(matcher.start(), end);
            String target = visibleUrl.regionMatches(true, 0, "www.", 0, 4) ? "https://" + visibleUrl : visibleUrl;
            append(line, visibleUrl, hint, new ClickEvent(ClickEvent.Action.OPEN_URL, target));
            offset = end;
            matcher.region(end, text.length());
        }
        append(line, text.substring(offset), hint, inheritedClick);
    }

    private String expand(String text, String[] groups, String server, String player, String world, Player sender) {
        String result = template(text, server, player, world, sender);
        for (int i = 0; i < groups.length; i++) result = result.replace("{" + i + "}", groups[i] == null ? "" : groups[i]);
        return result;
    }

    private String template(String text, String server, String player, String world, Player sender) {
        // 时间变量先替换（在玩家可控的昵称/目标等插入之前，避免其内容里的 ${...} 被当成变量展开）
        String result = TimePlaceholders.apply(text, java.time.ZonedDateTime.now())
                .replace("${server}", server).replace("${player}", player)
                .replace("${target}", privateTarget.get())
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
        if (url.startsWith("https://") || url.startsWith("http://")) {
            try {
                java.net.URI parsed = new java.net.URI(url);
                if (parsed.getHost() != null && parsed.getRawUserInfo() == null) {
                    return new ClickEvent(ClickEvent.Action.OPEN_URL, url);
                }
            } catch (java.net.URISyntaxException ignored) { }
        }
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

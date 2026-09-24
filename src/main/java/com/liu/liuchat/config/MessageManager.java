package com.liu.liuchat.config;

import com.liu.liuchat.model.MuteData;
import com.liu.liuchat.util.TextUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * messages.yml 语言访问层。
 * <p>
 * 关键约定：<b>先翻译模板自身的 & 颜色，再原样插入占位符的值</b> ——
 * 这样玩家可控的内容（如消息文本）不会被顺手把 & 也翻译成颜色，
 * 颜色权限（liuchat.color）才能真正拦得住。
 */
public final class MessageManager {

    private final JavaPlugin plugin;
    private YamlConfiguration messages;

    public MessageManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        messages = ConfigDefaults.load(plugin, "messages.yml");
    }

    public void reload() {
        load();
    }

    /** 原始文本（未替换占位符、未翻译颜色），语言项缺失时返回提示串 */
    public String getRaw(String key) {
        String value = messages.getString(key);
        return value != null ? value : "&c[缺失语言项: " + key + "]";
    }

    /** 替换占位符并翻译模板颜色，占位符的值原样插入 */
    public String get(String key, String... placeholders) {
        String line = TextUtil.color(getRaw(key));
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            line = line.replace(placeholders[i], placeholders[i + 1]);
        }
        return line;
    }

    /** 带 prefix 的完整消息 */
    public void send(CommandSender to, String key, String... placeholders) {
        to.sendMessage(get("prefix") + get(key, placeholders));
    }

    /**
     * 禁言剩余时长展示文本：永久走语言文件，剩余时长实时计算。
     */
    public String muteTimeText(MuteData mute) {
        return mute.isPermanent()
                ? get("mute.permanent")
                : TextUtil.formatDuration(mute.expireAt() - System.currentTimeMillis());
    }
}

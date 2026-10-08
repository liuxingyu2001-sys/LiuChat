package com.liu.liuchat.hook;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * CraftEngine 表情解析门面。
 *
 * <p>本类<b>不引用任何 CraftEngine 类型</b>：CraftEngine 是软依赖，未安装时直接加载
 * 引用它的类会触发 {@link NoClassDefFoundError}（JVM 校验方法体时会解析签名里的
 * 类型），即使方法开头就有可用性判断也来不及。真正的实现隔离在
 * {@link CraftEngineEmojiResolver}，只有 CraftEngine 已启用时才会被加载。
 */
public final class CraftEngineEmojiHook {
    private CraftEngineEmojiHook() { }

    /** CraftEngine 是否已安装并启用。 */
    public static boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("CraftEngine");
    }

    /** 解析消息里的 CE 表情关键字 -> {@code ce-json:...} 编码，供聊天渲染复用。 */
    public static Map<String, String> resolve(Player sender, String message) {
        if (sender == null || message == null || !available()) return Map.of();
        return CraftEngineEmojiResolver.resolve(sender, message);
    }

    /** 解析 CustomNameplates 气泡表情，未装 CE 时原样返回。 */
    public static String resolveBubble(Player sender, String message) {
        if (sender == null || message == null || message.isEmpty() || !available()) return message;
        return CraftEngineEmojiResolver.resolveBubble(sender, message);
    }

    /** 纯工具方法：合并命中（不涉及 CraftEngine 类，单测可直接调用）。 */
    public static void addMatches(Map<String, String> results, String message, String keyword, String glyph, String encoded) {
        if (message.contains(keyword) && results.size() < 16) results.putIfAbsent(keyword, encoded);
        if (!glyph.isEmpty() && !glyph.equals(keyword) && message.contains(glyph) && results.size() < 16)
            results.putIfAbsent(glyph, encoded);
    }
}

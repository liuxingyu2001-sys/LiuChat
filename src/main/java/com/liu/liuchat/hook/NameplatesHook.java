package com.liu.liuchat.hook;

import org.bukkit.Bukkit;

/**
 * CustomNameplates 图片节点门面。
 *
 * <p>本类不引用任何 CustomNameplates 类型；实现在 {@link NameplatesImageResolver}，
 * 仅在插件已启用时加载。
 */
public final class NameplatesHook {
    private NameplatesHook() { }

    /** 解析 CustomNameplates 图片为文本组件；未安装或解析失败时返回 null。 */
    public static String withImage(String text, String kind, String id, float left, float right) {
        if (!Bukkit.getPluginManager().isPluginEnabled("CustomNameplates")) return null;
        return NameplatesImageResolver.withImage(text, kind, id, left, right);
    }
}

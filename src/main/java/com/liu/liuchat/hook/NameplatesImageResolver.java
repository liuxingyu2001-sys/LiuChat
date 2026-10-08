package com.liu.liuchat.hook;

import net.momirealms.customnameplates.api.CustomNameplatesAPI;
import net.momirealms.customnameplates.api.feature.AdaptiveImage;

/**
 * CustomNameplates 图片解析的实际实现（类隔离），仅在插件已启用时加载。
 */
final class NameplatesImageResolver {
    private NameplatesImageResolver() { }

    static String withImage(String text, String kind, String id, float left, float right) {
        try {
            CustomNameplatesAPI api = CustomNameplatesAPI.getInstance();
            if (api == null) return null;
            AdaptiveImage image = switch (kind) {
                case "background" -> api.getBackground(id).orElse(null);
                case "nameplate" -> api.getNameplate(id).orElse(null);
                default -> null;
            };
            return image == null ? null : api.createTextWithImage(text, image, left, right);
        } catch (LinkageError | IllegalStateException ex) {
            return null;
        }
    }
}

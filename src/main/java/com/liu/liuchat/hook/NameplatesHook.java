package com.liu.liuchat.hook;

import net.momirealms.customnameplates.api.CustomNameplatesAPI;
import net.momirealms.customnameplates.api.feature.AdaptiveImage;
import org.bukkit.Bukkit;

/** Optional CustomNameplates image lookup; never invoked without the plugin. */
public final class NameplatesHook {
    private NameplatesHook() { }

    public static String withImage(String text, String kind, String id, float left, float right) {
        if (!Bukkit.getPluginManager().isPluginEnabled("CustomNameplates")) return null;
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

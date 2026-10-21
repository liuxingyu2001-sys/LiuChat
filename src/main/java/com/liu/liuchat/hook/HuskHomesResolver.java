package com.liu.liuchat.hook;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;

/**
 * 只做反射调用的薄层，便于用存根做单元测试（真实类由 HuskHomes 插件提供）。
 *
 * <p>调用等价于 HuskHomes 的 {@code /tp <玩家>}：
 * {@code HuskHomesAPI.teleportBuilder().teleporter(user).target(name).buildAndComplete(true)}。
 * 其中 {@code true} 表示使用 HuskHomes 自己的传送预热逻辑，与命令行为一致。</p>
 */
final class HuskHomesResolver {
    static final String API_CLASS = "net.william278.huskhomes.api.HuskHomesAPI";
    static final String TELEPORTABLE = "net.william278.huskhomes.teleport.Teleportable";

    private HuskHomesResolver() {
    }

    /**
     * 在给定类加载器下调用 HuskHomes API。
     *
     * @return HuskHomes 是否接受了这次传送（跨服时 true 代表已发起，非已落地）
     */
    static boolean invoke(ClassLoader loader, Player player, String targetName) throws ReflectiveOperationException {
        Class<?> apiClass = Class.forName(API_CLASS, true, loader);
        Object api = apiClass.getMethod("getInstance").invoke(null);
        Object user = apiClass.getMethod("adaptUser", Player.class).invoke(api, player);
        Object builder = apiClass.getMethod("teleportBuilder").invoke(api);
        Method teleporter = builder.getClass()
                .getMethod("teleporter", Class.forName(TELEPORTABLE, true, loader));
        builder = teleporter.invoke(builder, user);
        builder = builder.getClass().getMethod("target", String.class).invoke(builder, targetName);
        Object completed = builder.getClass()
                .getMethod("buildAndComplete", boolean.class, String[].class)
                .invoke(builder, Boolean.TRUE, new String[0]);
        return Boolean.TRUE.equals(completed);
    }
}

package com.liu.liuchat.hook;

import com.liu.liuchat.util.SpacePreview;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 可选接入 SoulSpace（灵魂空间）：读取玩家空间的只读预览数据。
 *
 * <p>本类不引用任何 {@code com.soulspace.api} 类型：未安装时直接加载引用它们的类会触发
 * {@link NoClassDefFoundError}（软依赖类隔离）。实现在 {@link SoulSpaceResolver}。
 *
 * <p>读取策略（每台服务器的每个展示条目只读一次，由调用方缓存）：
 * 本服在线玩家优先读 SoulSpace 内存（零 IO、含未保存改动），
 * 否则从存储读取一次——MySQL 多服共享下任意服务器都能读到同一份数据。
 */
public final class SoulSpaceHook {
    private SoulSpaceHook() { }

    public static boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("SoulSpace");
    }

    /**
     * 读取玩家空间内容（只读快照，携带真实数量供排序）。任意线程可调用。
     *
     * @return {@code Optional.empty()} = SoulSpace 不可用/调用失败（不应缓存，可重试）；
     *         {@code Optional.of(list)} = 读取成功（list 可为空 = 空间为空，可缓存）
     */
    public static CompletableFuture<Optional<List<SpacePreview.Counted<ItemStack>>>> fetch(UUID playerId) {
        if (!available()) return CompletableFuture.completedFuture(Optional.empty());
        return SoulSpaceResolver.fetch(playerId);
    }
}

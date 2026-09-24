package com.liu.liuchat.hook;

import com.liu.liuchat.util.SpacePreview;
import com.soulspace.api.SoulSpace;
import com.soulspace.api.SoulSpaceApi;
import com.soulspace.api.SpaceInfo;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 可选接入 SoulSpace（灵魂空间）：读取玩家空间的只读预览数据。
 *
 * <p>SoulSpace 未安装时 {@link #available()} 为 false，其余方法不应被调用
 * （com.soulspace.api 仅在实际执行时解析，软依赖缺失不影响本插件其余功能）。
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
     * 读取玩家空间内容（只读快照）。任意线程可调用，完成于主线程。
     *
     * @return {@code Optional.empty()} = SoulSpace 不可用/调用失败（不应缓存，可重试）；
     *         {@code Optional.of(list)} = 读取成功（list 可为空 = 空间为空，可缓存）
     */
    public static CompletableFuture<Optional<List<ItemStack>>> fetch(UUID playerId) {
        CompletableFuture<Optional<List<ItemStack>>> out = new CompletableFuture<>();
        try {
            SoulSpaceApi api = SoulSpace.getApi();
            if (api == null) {
                out.complete(Optional.empty());
                return out;
            }
            if (Bukkit.isPrimaryThread()) {
                Optional<SpaceInfo> cached = api.getSpaceIfLoaded(playerId);
                if (cached.isPresent()) {
                    out.complete(Optional.of(displayItems(cached.get())));
                    return out;
                }
            }
            api.fetchSpace(playerId).whenComplete((info, err) ->
                    out.complete(err != null || info == null ? Optional.empty()
                            : Optional.of(info.map(SoulSpaceHook::displayItems).orElseGet(List::of))));
        } catch (Throwable t) {
            Bukkit.getLogger().warning("LiuChat: 读取灵魂空间数据失败: " + t);
            out.complete(Optional.empty());
        }
        return out;
    }

    /** SpaceInfo -> GUI 展示物品：数量压到堆叠上限，超出部分写进 Lore（无限堆叠可远超 64）。 */
    private static List<ItemStack> displayItems(SpaceInfo info) {
        List<ItemStack> list = new ArrayList<>();
        for (SpaceInfo.StoredStack stack : info.stacks()) {
            ItemStack display = stack.cleanItem().clone();
            display.setAmount(SpacePreview.displayAmount(stack.count()));
            if (stack.count() > display.getAmount()) {
                ItemMeta meta = display.getItemMeta();
                if (meta != null) {
                    List<String> lore = meta.hasLore() && meta.getLore() != null
                            ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                    lore.add(SpacePreview.countLabel(stack.count()));
                    meta.setLore(lore);
                    display.setItemMeta(meta);
                }
            }
            list.add(display);
        }
        return list;
    }
}

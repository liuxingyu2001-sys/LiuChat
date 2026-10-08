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
 * SoulSpace 读取的实际实现（类隔离），仅在插件已启用时加载。
 */
final class SoulSpaceResolver {
    private SoulSpaceResolver() { }

    static CompletableFuture<Optional<List<SpacePreview.Counted<ItemStack>>>> fetch(UUID playerId) {
        CompletableFuture<Optional<List<SpacePreview.Counted<ItemStack>>>> out = new CompletableFuture<>();
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
                            : Optional.of(info.map(SoulSpaceResolver::displayItems).orElseGet(List::of))));
        } catch (Throwable t) {
            Bukkit.getLogger().warning("LiuChat: 读取灵魂空间数据失败: " + t);
            out.complete(Optional.empty());
        }
        return out;
    }

    /** SpaceInfo -> GUI 展示堆叠：数量压到堆叠上限，超出部分写进 Lore（无限堆叠可远超 64）。 */
    private static List<SpacePreview.Counted<ItemStack>> displayItems(SpaceInfo info) {
        List<SpacePreview.Counted<ItemStack>> list = new ArrayList<>();
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
            list.add(new SpacePreview.Counted<>(display, stack.count()));
        }
        return list;
    }
}

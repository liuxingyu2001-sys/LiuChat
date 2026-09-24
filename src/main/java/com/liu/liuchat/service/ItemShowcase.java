package com.liu.liuchat.service;

import com.liu.liuchat.hook.SoulSpaceHook;
import com.liu.liuchat.util.SpacePreview;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Snapshots displayed items; the GUI never exposes a mutable inventory item. */
public final class ItemShowcase implements Listener {
    private static final long LIFETIME = 10 * 60 * 1000L;
    // register() 在异步聊天线程执行，GUI 点击在主线程，必须并发安全
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final CraftEngineNames ceNames = new CraftEngineNames();
    private final VanillaItemNames vanillaNames = new VanillaItemNames();
    /** 灵魂空间预览配置（由 ChatPresentation 提供，reload 后自动生效）。 */
    private volatile SpaceSettings spaceSettings;

    public ItemShowcase() { ceNames.reload(); }
    public void reloadTranslations() { ceNames.reload(); }
    public void setSpaceSettings(SpaceSettings settings) { this.spaceSettings = settings; }

    /** 灵魂空间预览配置提供者。 */
    public interface SpaceSettings {
        boolean spacePreviewEnabled();
        String spaceRingKey();
        String spacePreviewPermission();
        String spaceSort();
    }

    private static final class Entry {
        final String owner;
        final UUID ownerUuid;
        final ItemStack item;
        final long expires;
        /** 空间预览数据：null = 尚未获取；首次点击获取一次后复用，条目过期即释放。 */
        volatile List<SpacePreview.Counted<ItemStack>> space;
        volatile boolean spaceLoading;

        Entry(String owner, UUID ownerUuid, ItemStack item, long expires) {
            this.owner = owner;
            this.ownerUuid = ownerUuid;
            this.item = item;
            this.expires = expires;
        }
    }

    public String snapshot(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() == Material.AIR) {
            return "";
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("item", hand.clone());
        String data = yaml.saveToString();
        // Leave room for the rest of the forwarded plugin message.
        return data.length() <= 16000 ? data : "";
    }

    public String register(String owner, String ownerUuid, String serialized) {
        if (serialized == null || serialized.isEmpty() || serialized.length() > 16000) {
            return null;
        }
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(serialized);
            ItemStack item = yaml.getItemStack("item");
            if (item == null || item.getType().isAir()) {
                return null;
            }
            if (entries.size() >= 512) {
                entries.entrySet().removeIf(e -> e.getValue().expires < System.currentTimeMillis());
                if (entries.size() >= 512) {
                    Iterator<String> keys = entries.keySet().iterator();
                    keys.next();
                    keys.remove();
                }
            }
            String id = UUID.randomUUID().toString();
            entries.put(id, new Entry(owner, parseUuid(ownerUuid), item.clone(),
                    System.currentTimeMillis() + LIFETIME));
            return id;
        } catch (Exception ex) {
            return null;
        }
    }

    public ItemStack item(String id) {
        Entry entry = entries.get(id);
        return entry == null || entry.expires < System.currentTimeMillis() ? null : entry.item.clone();
    }

    public String name(String id, int maxLength) {
        ItemStack item = item(id);
        if (item == null) {
            return "";
        }
        String shown = vanillaNames.resolve(item.getType());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) shown = preferredName(meta, ceNames, shown);
        shown = ceNames.resolve(shown);
        String name = org.bukkit.ChatColor.stripColor(com.liu.liuchat.util.TextUtil.color(shown));
        return name.length() > maxLength ? name.substring(0, maxLength) + "..." : name;
    }

    static String preferredName(ItemMeta meta, CraftEngineNames names, String vanilla) {
        if (meta.hasDisplayName()) {
            var custom = meta.displayName();
            return names.resolve(custom != null ? custom : net.kyori.adventure.text.Component.text(meta.getDisplayName()));
        }
        if (meta.hasItemName()) {
            var itemName = meta.itemName();
            return names.resolve(itemName != null ? itemName : net.kyori.adventure.text.Component.text(meta.getItemName()));
        }
        return vanilla;
    }

    /** 潜影盒（含 16 种染色）：点击展示消息打开盒内物品预览，而不是单个物品预览。 */
    public static boolean isShulkerBox(Material type) {
        return type == Material.SHULKER_BOX || type != null && type.name().endsWith("_SHULKER_BOX");
    }

    public void open(Player viewer, String id) {
        Entry entry = entries.get(id);
        if (entry == null || entry.expires < System.currentTimeMillis()) {
            viewer.sendMessage("§c该物品展示已过期。");
            return;
        }
        SpaceSettings settings = spaceSettings;
        if (settings != null && settings.spacePreviewEnabled() && SoulSpaceHook.available()
                && entry.ownerUuid != null && viewer.hasPermission(settings.spacePreviewPermission())
                && isSoulSpaceRing(entry.item, settings.spaceRingKey())) {
            openSpace(viewer, entry);
            return;
        }
        if (isShulkerBox(entry.item.getType())) {
            openShulker(viewer, entry.owner, entry.item);
            return;
        }
        ShowcaseHolder holder = new ShowcaseHolder();
        Inventory inventory = Bukkit.createInventory(holder, 27, entry.owner + " 展示的物品");
        holder.inventory = inventory;
        inventory.setItem(13, entry.item.clone());
        viewer.openInventory(inventory);
    }

    /** 潜影盒预览：27 格只读 GUI 展示盒内物品（空盒为空界面）。 */
    private void openShulker(Player viewer, String owner, ItemStack item) {
        ShowcaseHolder holder = new ShowcaseHolder();
        Inventory inventory = Bukkit.createInventory(holder, 27, owner + " 展示的潜影盒");
        holder.inventory = inventory;
        ItemStack[] contents = shulkerContents(item);
        for (int slot = 0; slot < 27 && slot < contents.length; slot++) {
            inventory.setItem(slot, contents[slot] == null ? null : contents[slot].clone());
        }
        viewer.openInventory(inventory);
    }

    /** 读取潜影盒物品的盒内数据；拿不到（无方块数据）时视为空盒。 */
    private static ItemStack[] shulkerContents(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof BlockStateMeta stateMeta) || !stateMeta.hasBlockState()) return new ItemStack[0];
        BlockState state = stateMeta.getBlockState();
        if (!(state instanceof Container container)) return new ItemStack[0];
        return container.getInventory().getContents();
    }

    /** 灵魂空间戒指识别：以 SoulSpace 的 PDC 标记为准（名称/材质可被伪造，PDC 不能）。 */
    public static boolean isSoulSpaceRing(ItemStack item, String pdcKey) {
        if (item == null || pdcKey == null || pdcKey.isBlank()) return false;
        NamespacedKey key = NamespacedKey.fromString(pdcKey.trim());
        if (key == null) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(key, PersistentDataType.INTEGER);
    }

    /**
     * 灵魂空间只读预览：数据每条目只在首次点击时读取一次（本服在线零 IO），
     * 缓存在条目上复用；读取失败不缓存，下次点击重试。
     */
    private void openSpace(Player viewer, Entry entry) {
        if (entry.space != null) {
            openSpaceGui(viewer, entry, 0, defaultSort());
            return;
        }
        if (entry.spaceLoading) {
            viewer.sendMessage("§7灵魂空间读取中，请稍候再点。");
            return;
        }
        entry.spaceLoading = true;
        viewer.sendMessage("§7正在读取灵魂空间…");
        SoulSpaceHook.fetch(entry.ownerUuid).whenComplete((result, err) -> {
            entry.spaceLoading = false;
            if (err != null || result == null || result.isEmpty()) {
                if (viewer.isOnline()) viewer.sendMessage("§c灵魂空间数据读取失败，请稍后再试。");
                return;
            }
            entry.space = result.get();
            if (viewer.isOnline()) openSpaceGui(viewer, entry, 0, defaultSort());
        });
    }

    /** 配置的默认排序方式（每次打开界面时读取，reload 后立即生效）。 */
    private SpacePreview.Sort defaultSort() {
        SpaceSettings settings = spaceSettings;
        return SpacePreview.Sort.parse(settings == null ? null : settings.spaceSort());
    }

    /** 空间预览 GUI：54 格只读，前 5 行 45 格放物品，末行排序与翻页（拿不走任何物品）。 */
    private void openSpaceGui(Player viewer, Entry entry, int page, SpacePreview.Sort sort) {
        List<SpacePreview.Counted<ItemStack>> items = entry.space == null ? List.of() : entry.space;
        items = SpacePreview.sort(items, SpacePreview.Counted::count, sort);
        int size = items.size();
        int pageCount = SpacePreview.pageCount(size);
        int index = SpacePreview.clampPage(page, pageCount);
        SpacePreviewHolder holder = new SpacePreviewHolder(entry, index, pageCount, sort);
        Inventory inventory = Bukkit.createInventory(holder, 54, entry.owner + " 的灵魂空间");
        holder.inventory = inventory;
        int from = SpacePreview.from(index, size);
        int to = SpacePreview.to(index, size);
        for (int i = from; i < to; i++) {
            inventory.setItem(i - from, items.get(i).item().clone());
        }
        if (index > 0) inventory.setItem(SpacePreview.NAV_PREV, navArrow("§e上一页"));
        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta infoMeta = info.getItemMeta();
        if (infoMeta != null) {
            infoMeta.setDisplayName("§f第 §e" + (index + 1) + " §f/ §e" + pageCount + " §f页");
            infoMeta.setLore(List.of("§7共 §f" + size + " §7种堆叠"));
            info.setItemMeta(infoMeta);
        }
        inventory.setItem(SpacePreview.NAV_INFO, info);
        ItemStack sortItem = new ItemStack(Material.HOPPER);
        ItemMeta sortMeta = sortItem.getItemMeta();
        if (sortMeta != null) {
            sortMeta.setDisplayName("§f排序：§e" + sort.label());
            sortMeta.setLore(List.of("§7点击切换排序方式"));
            sortItem.setItemMeta(sortMeta);
        }
        inventory.setItem(SpacePreview.NAV_SORT, sortItem);
        if (index < pageCount - 1) inventory.setItem(SpacePreview.NAV_NEXT, navArrow("§e下一页"));
        viewer.openInventory(inventory);
    }

    private static ItemStack navArrow(String name) {
        ItemStack arrow = new ItemStack(Material.ARROW);
        ItemMeta meta = arrow.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            arrow.setItemMeta(meta);
        }
        return arrow;
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static final class ShowcaseHolder implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class SpacePreviewHolder implements InventoryHolder {
        final Entry entry;
        final int page;
        final int pageCount;
        final SpacePreview.Sort sort;
        private Inventory inventory;

        SpacePreviewHolder(Entry entry, int page, int pageCount, SpacePreview.Sort sort) {
            this.entry = entry;
            this.page = page;
            this.pageCount = pageCount;
            this.sort = sort;
        }

        @Override public Inventory getInventory() { return inventory; }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof ShowcaseHolder) {
            event.setCancelled(true);
            return;
        }
        if (top.getHolder() instanceof SpacePreviewHolder holder) {
            event.setCancelled(true);
            if (event.getClickedInventory() != top || !(event.getWhoClicked() instanceof Player viewer)) {
                return;
            }
            if (event.getRawSlot() == SpacePreview.NAV_PREV && holder.page > 0) {
                openSpaceGui(viewer, holder.entry, holder.page - 1, holder.sort);
            } else if (event.getRawSlot() == SpacePreview.NAV_NEXT && holder.page < holder.pageCount - 1) {
                openSpaceGui(viewer, holder.entry, holder.page + 1, holder.sort);
            } else if (event.getRawSlot() == SpacePreview.NAV_SORT) {
                openSpaceGui(viewer, holder.entry, holder.page, holder.sort.next());
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof ShowcaseHolder || top.getHolder() instanceof SpacePreviewHolder) {
            event.setCancelled(true);
        }
    }
}

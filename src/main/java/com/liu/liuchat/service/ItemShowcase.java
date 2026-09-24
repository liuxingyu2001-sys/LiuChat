package com.liu.liuchat.service;

import org.bukkit.Bukkit;
import org.bukkit.Material;
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

import java.util.Iterator;
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

    public ItemShowcase() { ceNames.reload(); }
    public void reloadTranslations() { ceNames.reload(); }

    private record Entry(String owner, ItemStack item, long expires) { }

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

    public String register(String owner, String serialized) {
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
            entries.put(id, new Entry(owner, item.clone(), System.currentTimeMillis() + LIFETIME));
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

    private static final class ShowcaseHolder implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ShowcaseHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ShowcaseHolder) {
            event.setCancelled(true);
        }
    }
}

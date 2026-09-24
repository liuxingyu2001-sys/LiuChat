package com.liu.liuchat.service;

import org.bukkit.Bukkit;
import org.bukkit.Material;
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

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Snapshots displayed items; the GUI never exposes a mutable inventory item. */
public final class ItemShowcase implements Listener {
    private static final long LIFETIME = 10 * 60 * 1000L;
    private final Map<String, Entry> entries = new HashMap<>();
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
        if (meta != null) {
            net.kyori.adventure.text.Component component = meta.hasItemName() ? meta.itemName()
                    : meta.hasDisplayName() ? meta.displayName() : null;
            if (component != null) shown = ceNames.resolve(component);
            else if (meta.hasItemName() || meta.hasDisplayName())
                shown = ceNames.resolve(meta.hasItemName() ? meta.getItemName() : meta.getDisplayName());
        }
        shown = ceNames.resolve(shown);
        String name = org.bukkit.ChatColor.stripColor(com.liu.liuchat.util.TextUtil.color(shown));
        return name.length() > maxLength ? name.substring(0, maxLength) + "..." : name;
    }

    public void open(Player viewer, String id) {
        Entry entry = entries.get(id);
        if (entry == null || entry.expires < System.currentTimeMillis()) {
            viewer.sendMessage("§c该物品展示已过期。");
            return;
        }
        ShowcaseHolder holder = new ShowcaseHolder();
        Inventory inventory = Bukkit.createInventory(holder, 27, entry.owner + " 展示的物品");
        holder.inventory = inventory;
        inventory.setItem(13, entry.item.clone());
        viewer.openInventory(inventory);
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

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
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Snapshots displayed items; the GUI never exposes a mutable inventory item. */
public final class ItemShowcase implements Listener {
    private static final long LIFETIME = 10 * 60 * 1000L;
    // register() 在异步聊天线程执行，GUI 点击在主线程，必须并发安全
    private final JavaPlugin plugin;
    private volatile boolean closing;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final CraftEngineNames ceNames = new CraftEngineNames();
    private final VanillaItemNames vanillaNames = new VanillaItemNames();
    /** 灵魂空间预览配置（由 ChatPresentation 提供，reload 后自动生效）。 */
    private volatile SpaceSettings spaceSettings;

    public ItemShowcase(JavaPlugin plugin) {
        this.plugin = plugin;
        ceNames.reload();
    }

    /** Close only LiuChat preview inventories before the listener is unregistered on disable. */
    public void closePreviews() {
        closing = true;
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (isPreview(top)) player.closeInventory();
        }
        entries.clear();
    }

    private static boolean isPreview(Inventory inventory) {
        return inventory.getHolder() instanceof ShowcaseHolder
                || inventory.getHolder() instanceof BundlePreviewHolder
                || inventory.getHolder() instanceof SpacePreviewHolder;
    }
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
        /** 一个条目可含多件物品（[i12]、[盔甲] 等 token 展开的结果），顺序即展示顺序。 */
        final List<ItemStack> items;
        /** 每个 token 实际展示的件数，渲染时按 token 顺序取用。 */
        final int[] counts;
        final long expires;
        /** 空间预览数据：null = 尚未获取；首次点击获取一次后复用，条目过期即释放。 */
        volatile List<SpacePreview.Counted<ItemStack>> space;
        volatile boolean spaceLoading;

        Entry(String owner, UUID ownerUuid, List<ItemStack> items, int[] counts, long expires) {
            this.owner = owner;
            this.ownerUuid = ownerUuid;
            this.items = items;
            this.counts = counts;
            this.expires = expires;
        }

        /** 第 index 件物品；越界返回 null。 */
        ItemStack at(int index) {
            return index >= 0 && index < items.size() ? items.get(index) : null;
        }
    }

    /** 编码结果：data 为空表示无法展示；dropped 为因上限或预算被丢弃的件数。 */
    public record Encoded(String data, int dropped) { }

    /** 整条 itemData 的字符预算：同包还有 placeholders（≤8000）与消息本体，插件消息硬上限 32767。 */
    private static final int SNAPSHOT_BUDGET = 16000;
    /** 给 counts 列表留的余量，免得最后多出几十个字符把包撑爆。 */
    private static final int COUNTS_MARGIN = 128;

    /**
     * 把消息里的物品 token 解析成待编码堆叠，并给发送者必要的提示。
     * 空槽跳过；超过 maxCount 或预算的从尾部丢弃；每个 token 实际展示的件数随数据一起编码，
     * 否则接收端无法知道某个 token 该出几个 chip（空槽已跳过，件数不等于槽位数）。
     */
    public Encoded encode(Player sender, List<ItemTokens.Part> parts, int maxCount) {
        if (sender == null || parts == null || parts.isEmpty()) return new Encoded("", 0);
        List<ItemStack> stacks = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        int skipped = 0;
        for (ItemTokens.Part part : parts) {
            if (!(part instanceof ItemTokens.Token token)) continue;
            int shown = 0;
            for (ItemTokens.Ref ref : token.refs()) {
                if (maxCount > 0 && stacks.size() >= maxCount) {
                    skipped++;
                    continue;
                }
                ItemStack stack = resolve(sender, ref);
                if (stack == null || stack.getType().isAir()) continue;
                stacks.add(stack.clone());
                shown++;
            }
            counts.add(shown);
        }
        Encoded encoded = snapshot(stacks, counts);
        if (counts.isEmpty()) return new Encoded("", 0);
        int dropped = skipped + encoded.dropped();
        if (encoded.data().isEmpty()) {
            sender.sendMessage("§c手上没有可展示的物品，或物品数据超出跨服消息限制。");
            return new Encoded("", 0);
        }
        if (dropped > 0) {
            sender.sendMessage("§c超出展示上限或物品数据过大，已省略 " + dropped + " 件物品。");
        }
        return new Encoded(encoded.data(), dropped);
    }

    /** 按 token 里的槽位引用取物品；空槽返回 null。 */
    private static ItemStack resolve(Player player, ItemTokens.Ref ref) {
        org.bukkit.inventory.PlayerInventory inventory = player.getInventory();
        return switch (ref.kind()) {
            case MAIN_HAND -> inventory.getItemInMainHand();
            case OFFHAND -> inventory.getItemInOffHand();
            // 快捷栏左起第 1 格 = 存储槽 0
            case HOTBAR -> ref.index() >= 1 && ref.index() <= 9 ? inventory.getItem(ref.index() - 1) : null;
            case ARMOR -> switch (ref.index()) {
                case 0 -> inventory.getHelmet();
                case 1 -> inventory.getChestplate();
                case 2 -> inventory.getLeggings();
                case 3 -> inventory.getBoots();
                default -> null;
            };
        };
    }

    /** 顺序填充预算：装不下的从尾部丢弃，token 计数同步截断，保证件数与数据始终对齐。 */
    private static Encoded snapshot(List<ItemStack> stacks, List<Integer> counts) {
        if (stacks.isEmpty()) return new Encoded("", 0);
        YamlConfiguration yaml = new YamlConfiguration();
        int kept = 0;
        for (ItemStack stack : stacks) {
            // 第一件写在 item 下（旧版单件格式读这里），其余 i1、i2…
            String key = kept == 0 ? "item" : ("i" + kept);
            yaml.set(key, stack);
            if (yaml.saveToString().length() + COUNTS_MARGIN > SNAPSHOT_BUDGET) {
                yaml.set(key, null);
                break;
            }
            kept++;
        }
        if (kept == 0) return new Encoded("", stacks.size());
        yaml.set("counts", toList(truncate(counts, kept)));
        return new Encoded(yaml.saveToString(), stacks.size() - kept);
    }

    /** 从最后一个 token 往回扣件数，直到总数等于 kept。 */
    static int[] truncate(List<Integer> counts, int kept) {
        int[] out = new int[counts.size()];
        int sum = 0;
        for (int i = 0; i < out.length; i++) {
            out[i] = Math.max(0, counts.get(i));
            sum += out[i];
        }
        for (int i = out.length - 1; i >= 0 && sum > kept; i--) {
            int give = Math.min(sum - kept, out[i]);
            out[i] -= give;
            sum -= give;
        }
        return out;
    }

    private static List<Integer> toList(int[] counts) {
        List<Integer> out = new ArrayList<>(counts.length);
        for (int count : counts) out.add(count);
        return out;
    }

    public String snapshot(ItemStack hand) {
        if (hand == null || hand.getType().isAir()) return "";
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("item", hand.clone());
        String data = yaml.saveToString();
        // Leave room for the rest of the forwarded plugin message.
        return data.length() <= SNAPSHOT_BUDGET ? data : "";
    }

    public String register(String owner, String ownerUuid, String serialized) {
        if (serialized == null || serialized.isEmpty() || serialized.length() > SNAPSHOT_BUDGET) {
            return null;
        }
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(serialized);
            ItemStack first = yaml.getItemStack("item");
            if (first == null || first.getType().isAir()) {
                return null;
            }
            List<ItemStack> stacks = new ArrayList<>();
            stacks.add(first);
            for (int index = 1; ; index++) {
                ItemStack stack = yaml.getItemStack("i" + index);
                if (stack == null) break;
                stacks.add(stack);
            }
            int[] counts = counts(yaml.getIntegerList("counts"), stacks.size());
            if (entries.size() >= 512) {
                entries.entrySet().removeIf(e -> e.getValue().expires < System.currentTimeMillis());
                if (entries.size() >= 512) {
                    Iterator<String> keys = entries.keySet().iterator();
                    keys.next();
                    keys.remove();
                }
            }
            String id = UUID.randomUUID().toString();
            entries.put(id, new Entry(owner, parseUuid(ownerUuid), List.copyOf(stacks), counts,
                    System.currentTimeMillis() + LIFETIME));
            return id;
        } catch (Exception ex) {
            return null;
        }
    }

    /** 计数与实际堆叠对齐：多则截断，少则补给最后一个 token，既不漏展示也不越界。 */
    private static int[] counts(List<Integer> encoded, int total) {
        if (encoded.isEmpty()) return new int[]{total};
        int[] out = truncate(encoded, total);
        int sum = 0;
        for (int count : out) sum += count;
        if (sum < total && out.length > 0) out[out.length - 1] += total - sum;
        return out;
    }

    /** 复合 id：基 id + "#" + 序号，指向条目里的第 n 件物品（渲染与点击预览共用）。 */
    public static String subId(String base, int index) {
        return base + "#" + index;
    }

    static String baseId(String id) {
        if (id == null) return null;
        int at = id.lastIndexOf('#');
        return at < 0 ? id : id.substring(0, at);
    }

    static int stackIndex(String id) {
        if (id == null) return 0;
        int at = id.lastIndexOf('#');
        if (at < 0) return 0;
        try {
            return Integer.parseInt(id.substring(at + 1));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** 条目不存在或已过期时返回 null。 */
    private Entry entry(String id) {
        if (id == null) return null;
        Entry entry = entries.get(baseId(id));
        return entry == null || entry.expires < System.currentTimeMillis() ? null : entry;
    }

    public ItemStack item(String id) {
        Entry entry = entry(id);
        if (entry == null) return null;
        ItemStack stack = entry.at(stackIndex(id));
        return stack == null ? null : stack.clone();
    }

    /** 每个 token 实际展示的件数（渲染时按 token 顺序取用）。 */
    public int[] counts(String id) {
        Entry entry = entry(id);
        return entry == null ? null : entry.counts.clone();
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

    /** 原版收纳袋（含染色款）。 */
    public static boolean isBundle(Material type) {
        return type == Material.BUNDLE || type != null && type.name().endsWith("_BUNDLE");
    }

    public void open(Player viewer, String id) {
        if (closing) return;
        Entry entry = entry(id);
        ItemStack stack = entry == null ? null : entry.at(stackIndex(id));
        if (entry == null || stack == null) {
            viewer.sendMessage("§c该物品展示已过期。");
            return;
        }
        SpaceSettings settings = spaceSettings;
        if (settings != null && settings.spacePreviewEnabled() && SoulSpaceHook.available()
                && entry.ownerUuid != null && viewer.hasPermission(settings.spacePreviewPermission())
                && isSoulSpaceRing(stack, settings.spaceRingKey())) {
            openSpace(viewer, entry);
            return;
        }
        if (isShulkerBox(stack.getType())) {
            openShulker(viewer, entry.owner, stack);
            return;
        }
        if (isBundle(stack.getType())) {
            openBundle(viewer, entry, stack, 0);
            return;
        }
        ShowcaseHolder holder = new ShowcaseHolder();
        Inventory inventory = Bukkit.createInventory(holder, 27, entry.owner + " 展示的物品");
        holder.inventory = inventory;
        inventory.setItem(13, stack.clone());
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

    /** 收纳袋内容来自展示时的物品快照；末行仅在需要翻页时放导航按钮。 */
    private void openBundle(Player viewer, Entry entry, ItemStack item, int page) {
        ItemMeta meta = item.getItemMeta();
        List<ItemStack> contents = meta instanceof BundleMeta bundle ? bundle.getItems() : List.of();
        int pageCount = SpacePreview.pageCount(contents.size());
        int index = SpacePreview.clampPage(page, pageCount);
        BundlePreviewHolder holder = new BundlePreviewHolder(entry, item, index, pageCount);
        Inventory inventory = Bukkit.createInventory(holder, contents.size() > 27 ? 54 : 27,
                entry.owner + " 展示的收纳袋");
        holder.inventory = inventory;
        int from = SpacePreview.from(index, contents.size());
        int to = SpacePreview.to(index, contents.size());
        for (int i = from; i < to; i++) {
            inventory.setItem(i - from, contents.get(i).clone());
        }
        if (pageCount > 1) {
            if (index > 0) inventory.setItem(SpacePreview.NAV_PREV, navArrow("§e上一页"));
            ItemStack info = new ItemStack(Material.PAPER);
            ItemMeta infoMeta = info.getItemMeta();
            if (infoMeta != null) {
                infoMeta.setDisplayName("§f第 §e" + (index + 1) + " §f/ §e" + pageCount + " §f页");
                info.setItemMeta(infoMeta);
            }
            inventory.setItem(SpacePreview.NAV_INFO, info);
            if (index < pageCount - 1) inventory.setItem(SpacePreview.NAV_NEXT, navArrow("§e下一页"));
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
            if (closing) return;
            Runnable finish = () -> {
                if (closing) return;
                entry.spaceLoading = false;
                if (err != null || result == null || result.isEmpty()) {
                    if (viewer.isOnline()) viewer.sendMessage("§c灵魂空间数据读取失败，请稍后再试。");
                    return;
                }
                entry.space = result.get();
                if (viewer.isOnline()) openSpaceGui(viewer, entry, 0, defaultSort());
            };
            if (plugin.isEnabled()) {
                try {
                    // 投递到查看者所在区域线程：Folia 下 GUI 必须在玩家区域打开
                    com.liu.liuchat.util.Schedulers.runFor(plugin, viewer, finish);
                } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
                    // Plugin disable may have started after the enabled check.
                }
            }
        });
    }

    /** 配置的默认排序方式（每次打开界面时读取，reload 后立即生效）。 */
    private SpacePreview.Sort defaultSort() {
        SpaceSettings settings = spaceSettings;
        return SpacePreview.Sort.parse(settings == null ? null : settings.spaceSort());
    }

    /** 空间预览 GUI：54 格只读，前 5 行 45 格放物品，末行排序与翻页（拿不走任何物品）。 */
    private void openSpaceGui(Player viewer, Entry entry, int page, SpacePreview.Sort sort) {
        if (closing) return;
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

    private static final class BundlePreviewHolder implements InventoryHolder {
        final Entry entry;
        /** 展示的是条目里的哪一件（多件展示时每个 chip 各自打开自己的收纳袋）。 */
        final ItemStack item;
        final int page;
        final int pageCount;
        private Inventory inventory;

        BundlePreviewHolder(Entry entry, ItemStack item, int page, int pageCount) {
            this.entry = entry;
            this.item = item;
            this.page = page;
            this.pageCount = pageCount;
        }

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
        if (top.getHolder() instanceof BundlePreviewHolder holder) {
            event.setCancelled(true);
            if (event.getClickedInventory() != top || !(event.getWhoClicked() instanceof Player viewer)) return;
            if (event.getRawSlot() == SpacePreview.NAV_PREV && holder.page > 0) {
                openBundle(viewer, holder.entry, holder.item, holder.page - 1);
            } else if (event.getRawSlot() == SpacePreview.NAV_NEXT && holder.page < holder.pageCount - 1) {
                openBundle(viewer, holder.entry, holder.item, holder.page + 1);
            }
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
        if (isPreview(top)) {
            event.setCancelled(true);
        }
    }
}

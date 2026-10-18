package com.liu.liuchat.service;

import com.liu.liuchat.api.Friend;
import com.liu.liuchat.api.FriendRequest;
import com.liu.liuchat.api.FriendResult;
import com.liu.liuchat.api.Mail;
import com.liu.liuchat.api.MailDraft;
import com.liu.liuchat.api.MailResult;
import com.liu.liuchat.config.ConfigManager;
import com.liu.liuchat.config.MessageManager;
import com.liu.liuchat.model.PlayerIdentity;
import com.liu.liuchat.util.Schedulers;
import com.liu.liuchat.util.TextUtil;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.ItemBuilder;
import net.momirealms.sparrow.ui.item.click.ItemClick;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.window.AnvilWindow;
import net.momirealms.sparrow.ui.window.NormalWindow;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 好友/邮件的 Sparrow UI 窗口。列表与按钮使用箱子窗口，短文本输入使用铁砧，
 * 邮件正文保留 Paper Dialog（原版/Sparrow 没有多行文本输入控件）。
 */
public final class SparrowSocialMenuService implements Listener, SocialMenu {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final int CONTENT = 45;

    private final JavaPlugin plugin;
    private final FriendService friends;
    private final MailService mail;
    private final PlayerDirectoryService directory;
    private final MessageManager messages;
    private final ConfigManager config;
    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();

    public SparrowSocialMenuService(JavaPlugin plugin, FriendService friends, MailService mail,
                                    PlayerDirectoryService directory, MessageManager messages, ConfigManager config) {
        this.plugin = plugin;
        this.friends = friends;
        this.mail = mail;
        this.directory = directory;
        this.messages = messages;
        this.config = config;
    }

    @Override
    public void closeAll() {
        for (Window window : new ArrayList<>(windows.values())) {
            try {
                window.close();
            } catch (RuntimeException ignored) {
                // 关闭期窗口可能已被 Sparrow 收走
            }
        }
        windows.clear();
    }

    // ---------------- 好友 ----------------

    @Override
    public void openFriendMain(Player player) {
        if (!friends.isEnabled()) {
            messages.send(player, "friend.disabled");
            return;
        }
        openFriendPage(player, 1);
    }

    /** 主界面直接展示好友，避免每次先点“好友列表”。 */
    private void openFriendPage(Player player, int page) {
        friends.friends(player.getUniqueId()).thenAccept(all -> main(player, () -> {
            Map<Integer, Item> items = new ConcurrentHashMap<>();
            int pageSize = Math.max(1, Math.min(CONTENT, config.friendsPageSize()));
            int pages = Math.max(1, (all.size() + pageSize - 1) / pageSize);
            int current = Math.min(Math.max(1, page), pages);
            int from = (current - 1) * pageSize;
            int to = Math.min(all.size(), from + pageSize);
            int online = 0;
            for (Friend friend : all) {
                if (friend.online()) online++;
            }
            for (int i = from; i < to; i++) {
                Friend friend = all.get(i);
                int slot = i - from;
                Consumer<ItemClick> action = click -> {
                    if (click.clickType().isRightClick()) {
                        openConfirm(click.player(), "删除好友 " + friend.name(), confirm ->
                                friends.removeName(confirm.getUniqueId(), confirm.getName(), friend.name())
                                        .thenAccept(result -> afterFriend(confirm, result, "friend.removed",
                                                friend.name(), () -> openFriendPage(confirm, current))));
                    } else {
                        openCompose(click.player(), friend.name());
                    }
                };
                items.put(slot, head(friend.name(), friend.uuid(), friend.name(),
                        List.of(friend.online() ? "&a在线" : "&7离线",
                                "&7成为好友: &f" + TIME.format(Instant.ofEpochMilli(friend.since())
                                        .atZone(ZoneId.systemDefault())),
                                "&a左键写邮件", "&c右键删除好友"), action));
            }
            if (all.isEmpty()) {
                items.put(22, button(Material.COBWEB, "&7还没有好友", List.of("&7点击下方“添加好友”"), null));
            }
            items.put(45, button(Material.BELL, "&e好友请求", List.of("&7查看收到的好友请求"),
                    click -> openFriendRequests(click.player(), 1)));
            items.put(46, button(Material.PAPER, "&7已发送请求", List.of("&7查看等待对方处理的请求"),
                    click -> openFriendSent(click.player(), 1)));
            items.put(47, button(Material.WRITABLE_BOOK, "&a添加好友", List.of("&7在线玩家一键添加 / 手动输入"),
                    click -> openAddFriendPicker(click.player())));
            if (current > 1) {
                items.put(48, button(Material.SPECTRAL_ARROW, "&e上一页",
                        List.of("&7第 " + (current - 1) + " 页"),
                        click -> openFriendPage(click.player(), current - 1)));
            }
            if (current < pages) {
                items.put(50, button(Material.SPECTRAL_ARROW, "&e下一页",
                        List.of("&7第 " + (current + 1) + " 页"),
                        click -> openFriendPage(click.player(), current + 1)));
            }
            items.put(49, button(Material.PAPER, "&7第 " + current + " / " + pages + " 页",
                    List.of("&7好友 " + all.size() + " 人，在线 " + online + " 人"), null));
            items.put(53, button(Material.BARRIER, "&c关闭", List.of("&7点击关闭界面"),
                    click -> click.window().close()));
            open(player, normal(player, "好友 " + online + "/" + all.size(), 6, items));
        }));
    }

    @Override
    public void openFriendRequests(Player player, int page) {
        friends.incomingRequests(player.getUniqueId()).thenAccept(requests -> main(player, () -> {
            Map<Integer, Item> items = new ConcurrentHashMap<>();
            if (requests.isEmpty()) {
                items.put(22, button(Material.COBWEB, "&7没有待处理请求", List.of("&7收到请求后会出现在这里"), null));
            }
            int pageSize = Math.max(1, Math.min(CONTENT, config.friendsPageSize()));
            int pages = Math.max(1, (requests.size() + pageSize - 1) / pageSize);
            int current = Math.min(Math.max(1, page), pages);
            int from = (current - 1) * pageSize;
            int to = Math.min(requests.size(), from + pageSize);
            for (int i = from; i < to; i++) {
                FriendRequest request = requests.get(i);
                int slot = i - from;
                items.put(slot, head(request.senderName(), request.senderId(), request.senderName(),
                        List.of("&7请求添加你为好友",
                                "&7时间: &f" + TIME.format(Instant.ofEpochMilli(request.createdAt())
                                        .atZone(ZoneId.systemDefault())),
                                "&a左键接受", "&c右键拒绝"), click -> {
                            if (click.clickType().isRightClick()) {
                                friends.denyName(click.player().getUniqueId(), click.player().getName(),
                                                request.senderName())
                                        .thenAccept(result -> afterFriend(click.player(), result, "friend.denied",
                                                request.senderName(), () -> openFriendRequests(click.player(), current)));
                            } else {
                                friends.acceptName(click.player().getUniqueId(), click.player().getName(),
                                                request.senderName())
                                        .thenAccept(result -> afterFriend(click.player(), result, "friend.accepted",
                                                request.senderName(), () -> openFriendRequests(click.player(), current)));
                            }
                        }));
            }
            nav(items, current, pages,
                    p -> openFriendRequests(p, current - 1),
                    p -> openFriendRequests(p, current + 1),
                    this::openFriendMain);
            open(player, normal(player, "好友请求", 6, items));
        }));
    }

    @Override
    public void openFriendSent(Player player, int page) {
        friends.outgoingRequests(player.getUniqueId()).thenAccept(requests -> main(player, () -> {
            Map<Integer, Item> items = new ConcurrentHashMap<>();
            if (requests.isEmpty()) {
                items.put(22, button(Material.COBWEB, "&7没有已发送请求", List.of("&7添加好友后会出现在这里"), null));
            }
            int pageSize = Math.max(1, Math.min(CONTENT, config.friendsPageSize()));
            int pages = Math.max(1, (requests.size() + pageSize - 1) / pageSize);
            int current = Math.min(Math.max(1, page), pages);
            int from = (current - 1) * pageSize;
            int to = Math.min(requests.size(), from + pageSize);
            for (int i = from; i < to; i++) {
                FriendRequest request = requests.get(i);
                int slot = i - from;
                items.put(slot, head(request.targetName(), request.targetId(), request.targetName(),
                        List.of("&7等待对方处理", "&7左键撤回请求"), click ->
                                friends.cancelName(click.player().getUniqueId(), click.player().getName(),
                                                request.targetName())
                                        .thenAccept(result -> afterFriend(click.player(), result, "friend.cancelled",
                                                request.targetName(), () -> openFriendSent(click.player(), current)))));
            }
            nav(items, current, pages,
                    p -> openFriendSent(p, current - 1),
                    p -> openFriendSent(p, current + 1),
                    this::openFriendMain);
            open(player, normal(player, "已发送的请求", 6, items));
        }));
    }

    /** 先列出本服在线玩家，点击直接发请求；离线玩家再走铁砧输入。 */
    private void openAddFriendPicker(Player player) {
        openAddFriendPicker(player, 1);
    }

    private void openAddFriendPicker(Player player, int page) {
        friends.friends(player.getUniqueId()).thenAccept(all -> main(player, () -> {
            Map<Integer, Item> items = new ConcurrentHashMap<>();
            java.util.Set<String> friendIds = new java.util.HashSet<>();
            for (Friend friend : all) friendIds.add(friend.uuid().toString());
            java.util.List<Player> online = new java.util.ArrayList<>();
            for (Player target : Bukkit.getOnlinePlayers()) {
                if (target.equals(player) || friendIds.contains(target.getUniqueId().toString())) continue;
                online.add(target);
            }
            online.sort(java.util.Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
            int pages = Math.max(1, (online.size() + CONTENT - 1) / CONTENT);
            int current = Math.min(Math.max(1, page), pages);
            int from = (current - 1) * CONTENT;
            int to = Math.min(online.size(), from + CONTENT);
            for (int i = from; i < to; i++) {
                Player target = online.get(i);
                items.put(i - from, head(target.getName(), target.getUniqueId(), target.getName(),
                        List.of("&7点击发送好友请求", "&7无需输入名字"), click ->
                                friends.request(click.player().getUniqueId(), click.player().getName(),
                                                target.getUniqueId(), target.getName())
                                        .thenAccept(result -> afterFriend(click.player(), result, "friend.ok",
                                                target.getName(), () -> openFriendMain(click.player())))));
            }
            if (online.isEmpty()) {
                items.put(22, button(Material.COBWEB, "&7没有可添加的在线玩家",
                        List.of("&7可以点击下方“手动输入”查找离线玩家"), null));
            }
            items.put(45, button(Material.ANVIL, "&e手动输入名字", List.of("&7添加离线或跨服玩家"),
                    click -> openAddFriendDialog(click.player())));
            if (current > 1) {
                items.put(48, button(Material.SPECTRAL_ARROW, "&e上一页", List.of("&7第 " + (current - 1) + " 页"),
                        click -> openAddFriendPicker(click.player(), current - 1)));
            }
            if (current < pages) {
                items.put(50, button(Material.SPECTRAL_ARROW, "&e下一页", List.of("&7第 " + (current + 1) + " 页"),
                        click -> openAddFriendPicker(click.player(), current + 1)));
            }
            items.put(49, button(Material.PAPER, "&7第 " + current + " / " + pages + " 页",
                    List.of("&7好友 " + all.size() + " 人"), null));
            items.put(53, button(Material.ARROW, "&7返回好友", List.of("&7点击返回"),
                    click -> openFriendMain(click.player())));
            open(player, normal(player, "添加好友", 6, items));
        }));
    }

    @Override
    public void openAddFriendDialog(Player player) {
        AtomicReference<String> input = new AtomicReference<>("");
        Map<Integer, Item> buttons = new ConcurrentHashMap<>();
        buttons.put(27, button(Material.RED_STAINED_GLASS_PANE, "&c取消", List.of("&7返回好友界面"),
                click -> { click.window().close(); openFriendMain(click.player()); }));
        buttons.put(31, button(Material.LIME_STAINED_GLASS_PANE, "&a发送请求", List.of("&7输入玩家名后点击"),
                click -> {
                    String name = inputText(click, input);
                    click.window().close();
                    if (name == null || name.isBlank()) {
                        messages.send(click.player(), "friend.not-found", "${player}", "");
                        openFriendMain(click.player());
                        return;
                    }
                    String stripped = name.strip();
                    directory.find(stripped).thenAccept(target -> {
                        if (target.isEmpty()) {
                            messages.send(click.player(), "friend.not-found", "${player}", stripped);
                            main(click.player(), () -> openFriendMain(click.player()));
                            return;
                        }
                        PlayerIdentity identity = target.get();
                        friends.request(click.player().getUniqueId(), click.player().getName(),
                                        UUID.fromString(identity.uuid()), identity.name())
                                .thenAccept(result -> afterFriend(click.player(), result, "friend.ok",
                                        identity.name(), () -> openFriendMain(click.player())));
                    });
                }));
        AnvilWindow window = AnvilWindow.builder()
                .setTitle(title("添加好友"))
                .setUpperPane(Pane.builder(3, 1).setFrozen(true).build())
                .setLowerPane(filledPane(4, buttons))
                .setTextFieldAlwaysEnabled(true)
                .addRenameHandler(input::set)
                .build(player);
        open(player, window);
    }

    // ---------------- 邮件 ----------------

    @Override
    public void openMailList(Player player) {
        openMailList(player, 1);
    }

    private void openMailList(Player player, int page) {
        if (!mail.isEnabled()) {
            messages.send(player, "mail.disabled");
            return;
        }
        mail.inbox(player.getUniqueId(), page).thenAccept(result -> main(player, () -> {
            Map<Integer, Item> items = new ConcurrentHashMap<>();
            if (result.total() == 0) {
                items.put(22, button(Material.COBWEB, "&7邮箱是空的", List.of("&7点击右下角写邮件"), null));
            }
            int pages = Math.max(1, (result.total() + result.pageSize() - 1) / result.pageSize());
            int current = Math.min(Math.max(1, page), pages);
            for (int i = 0; i < result.mails().size() && i < CONTENT; i++) {
                Mail entry = result.mails().get(i);
                List<Component> lore = new ArrayList<>();
                lore.add(line("&7发件人: &f", entry.senderName()));
                lore.add(line("&7时间: &f", TIME.format(Instant.ofEpochMilli(entry.createdAt())
                        .atZone(ZoneId.systemDefault()))));
                lore.add(text(entry.read() ? "&7状态: &7已读" : "&e状态: 未读"));
                lore.add(line("&7附件: &f", String.valueOf(entry.attachments().size()))
                        .append(entry.claimed() ? text(" &7(已领取)") : Component.empty()));
                lore.add(text("&a左键阅读"));
                lore.add(text("&c右键删除"));
                items.put(i, button(entry.read() ? Material.BOOK : Material.WRITTEN_BOOK,
                        text("&e").append(user(entry.subject())), lore, click -> {
                            if (click.clickType().isRightClick()) {
                                openConfirm(click.player(), "删除邮件 " + entry.subject(), confirm ->
                                        mail.delete(confirm.getUniqueId(), entry.id())
                                                .thenAccept(result1 -> afterMail(confirm, result1, "mail.delete-ok",
                                                        () -> openMailList(confirm, current))));
                            } else {
                                openMailDetail(click.player(), entry.id());
                            }
                        }));
            }
            nav(items, current, pages,
                    p -> openMailList(p, current - 1),
                    p -> openMailList(p, current + 1),
                    Player::closeInventory);
            items.put(53, button(Material.WRITABLE_BOOK, "&a写邮件",
                    List.of("&7输入收件人，填写主题与正文", "&7可附带主手物品"), click -> openCompose(click.player())));
            open(player, normal(player, "邮箱", 6, items));
        }));
    }

    private void openMailDetail(Player player, String mailId) {
        mail.markRead(player.getUniqueId(), mailId)
                .thenCompose(ignored -> mail.mail(player.getUniqueId(), mailId))
                .thenAccept(found -> main(player, () -> {
                    if (found.isEmpty()) {
                        messages.send(player, "mail.not-found");
                        openMailList(player, 1);
                        return;
                    }
                    renderMailDetail(player, found.get());
                }));
    }

    private void renderMailDetail(Player player, Mail entry) {
        Map<Integer, Item> items = new ConcurrentHashMap<>();
        List<Component> info = new ArrayList<>();
        info.add(line("&7发件人: &f", entry.senderName()));
        info.add(line("&7时间: &f", TIME.format(Instant.ofEpochMilli(entry.createdAt())
                .atZone(ZoneId.systemDefault()))));
        info.add(entry.claimed() ? text("&7附件: &7已领取")
                : line("&7附件: &f", String.valueOf(entry.attachments().size())));
        info.add(text("&7正文:"));
        String[] lines = entry.body().split("\\R", -1);
        int max = Math.min(8, lines.length);
        for (int i = 0; i < max; i++) {
            String line = lines[i];
            info.add(line("&f", line.length() > 42 ? line.substring(0, 42) + "..." : line));
        }
        if (lines.length > max) info.add(text("&8..."));
        items.put(4, button(Material.PAPER, text("&e").append(user(entry.subject())), info, null));

        if (!entry.claimed()) {
            int slot = 18;
            for (ItemStack item : entry.attachments()) {
                if (slot >= 27) break;
                items.put(slot, item(item, click -> claimMail(click.player(), entry.id())));
                slot++;
            }
        }
        items.put(45, button(Material.ARROW, "&7返回邮箱", List.of("&7点击返回"),
                click -> openMailList(click.player(), 1)));
        if (!entry.attachments().isEmpty() && !entry.claimed()) {
            items.put(49, button(Material.CHEST, "&a领取附件",
                    List.of("&7把全部附件放入背包", "&7背包不足时不会领取"),
                    click -> claimMail(click.player(), entry.id())));
        } else {
            items.put(49, button(entry.claimed() ? Material.CHEST : Material.BARRIER,
                    entry.claimed() ? "&7附件已领取" : "&7没有附件",
                    List.of(entry.claimed() ? "&7不能重复领取" : "&7这封邮件只有正文"), null));
        }
        items.put(53, button(Material.BARRIER, "&c删除邮件", List.of("&7点击后需要确认"),
                click -> openConfirm(click.player(), "删除邮件 " + entry.subject(), confirm ->
                        mail.delete(confirm.getUniqueId(), entry.id())
                                .thenAccept(result -> afterMail(confirm, result, "mail.delete-ok",
                                        () -> openMailList(confirm, 1))))));
        open(player, normal(player, "邮件", 6, items));
    }

    private void claimMail(Player player, String mailId) {
        mail.claim(player, mailId).thenAccept(result -> afterMail(player, result, "mail.claim-ok",
                () -> openMailDetail(player, mailId)));
    }

    @Override
    public void openCompose(Player player) {
        openCompose(player, "");
    }

    private void openCompose(Player player, String initialRecipient) {
        if (!mail.isEnabled()) {
            messages.send(player, "mail.disabled");
            return;
        }
        if (initialRecipient != null && !initialRecipient.isBlank()) {
            openComposeDialog(player, initialRecipient);
            return;
        }
        openRecipientPicker(player);
    }

    /** 优先列出好友和本服在线玩家，点击选择；离线/跨服收件人再手动输入。 */
    private void openRecipientPicker(Player player) {
        openRecipientPicker(player, 1);
    }

    private void openRecipientPicker(Player player, int page) {
        friends.friends(player.getUniqueId()).thenAccept(all -> main(player, () -> {
            Map<Integer, Item> items = new ConcurrentHashMap<>();
            java.util.Map<UUID, ComposeTarget> targets = new java.util.LinkedHashMap<>();
            for (Friend friend : all) {
                Player online = Bukkit.getPlayer(friend.uuid());
                targets.put(friend.uuid(), new ComposeTarget(friend.name(), friend.uuid(), true, online != null));
            }
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.equals(player)) continue;
                targets.putIfAbsent(online.getUniqueId(), new ComposeTarget(online.getName(),
                        online.getUniqueId(), false, true));
            }
            java.util.List<ComposeTarget> list = new java.util.ArrayList<>(targets.values());
            list.sort(java.util.Comparator.comparing(ComposeTarget::friend).reversed()
                    .thenComparing(ComposeTarget::name, String.CASE_INSENSITIVE_ORDER));
            int pages = Math.max(1, (list.size() + CONTENT - 1) / CONTENT);
            int current = Math.min(Math.max(1, page), pages);
            int from = (current - 1) * CONTENT;
            int to = Math.min(list.size(), from + CONTENT);
            for (int i = from; i < to; i++) {
                ComposeTarget target = list.get(i);
                items.put(i - from, head(target.name(), target.uuid(), target.name(),
                        List.of(target.friend() ? "&a好友" : "&7在线玩家",
                                target.online() ? "&a在线" : "&7离线",
                                "&7点击选择收件人"), click ->
                                openComposeDialog(click.player(), target.name())));
            }
            if (list.isEmpty()) {
                items.put(22, button(Material.COBWEB, "&7没有可选择的收件人",
                        List.of("&7点击下方“手动输入”"), null));
            }
            items.put(45, button(Material.ANVIL, "&e手动输入名字", List.of("&7输入离线/跨服玩家"),
                    click -> openRecipientAnvil(click.player())));
            if (current > 1) {
                items.put(48, button(Material.SPECTRAL_ARROW, "&e上一页", List.of("&7第 " + (current - 1) + " 页"),
                        click -> openRecipientPicker(click.player(), current - 1)));
            }
            if (current < pages) {
                items.put(50, button(Material.SPECTRAL_ARROW, "&e下一页", List.of("&7第 " + (current + 1) + " 页"),
                        click -> openRecipientPicker(click.player(), current + 1)));
            }
            items.put(49, button(Material.PAPER, "&7第 " + current + " / " + pages + " 页",
                    List.of("&7好友与在线玩家"), null));
            items.put(53, button(Material.ARROW, "&7返回邮箱", List.of("&7点击返回"),
                    click -> openMailList(click.player())));
            open(player, normal(player, "选择收件人", 6, items));
        }));
    }

    private void openRecipientAnvil(Player player) {
        AtomicReference<String> input = new AtomicReference<>("");
        Map<Integer, Item> buttons = new ConcurrentHashMap<>();
        buttons.put(27, button(Material.RED_STAINED_GLASS_PANE, "&c取消", List.of("&7返回邮箱"),
                click -> { click.window().close(); openMailList(click.player()); }));
        buttons.put(31, button(Material.LIME_STAINED_GLASS_PANE, "&a下一步", List.of("&7填写主题与正文"),
                click -> {
                    String recipient = inputText(click, input);
                    click.window().close();
                    if (recipient == null || recipient.isBlank()) {
                        messages.send(click.player(), "mail.player-not-found", "${player}", "");
                        openMailList(click.player());
                        return;
                    }
                    openComposeDialog(click.player(), recipient.strip());
                }));
        AnvilWindow window = AnvilWindow.builder()
                .setTitle(title("收件人"))
                .setUpperPane(Pane.builder(3, 1).setFrozen(true).build())
                .setLowerPane(filledPane(4, buttons))
                .setTextFieldAlwaysEnabled(true)
                .addRenameHandler(input::set)
                .build(player);
        open(player, window);
    }

    private record ComposeTarget(String name, UUID uuid, boolean friend, boolean online) { }

    private void openComposeDialog(Player player, String recipient) {
        TextDialogInput.Builder body = DialogInput.text("body", text("正文"))
                .maxLength(config.mailMaxBody())
                .multiline(TextDialogInput.MultilineOptions.create(10, 120));
        ActionButton send = ActionButton.create(
                text("发送"), text("发送这封邮件"), 160,
                DialogAction.customClick(
                        (view, audience) -> main(player, () -> sendFromDialog(player, recipient, view)),
                        ClickCallback.Options.builder().uses(1).build()));
        ActionButton cancel = ActionButton.create(
                text("取消"), Component.empty(), 100,
                DialogAction.customClick((view, audience) -> { },
                        ClickCallback.Options.builder().uses(1).build()));
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(text("写邮件"))
                        .inputs(List.of(
                                DialogInput.text("subject", text("主题"))
                                        .maxLength(config.mailMaxSubject()).build(),
                                body.build(),
                                DialogInput.bool("attach", text("附带主手物品"), false, "是", "否")))
                        .build())
                .type(DialogType.multiAction(List.of(send), cancel, 1)));
        player.showDialog(dialog);
    }

    private void sendFromDialog(Player player, String recipient, DialogResponseView view) {
        String subject = view.getText("subject");
        String body = view.getText("body");
        boolean attach = Boolean.TRUE.equals(view.getBoolean("attach"));
        directory.find(recipient).thenAccept(target -> {
            if (target.isEmpty()) {
                messages.send(player, "mail.player-not-found", "${player}", recipient);
                return;
            }
            PlayerIdentity identity = target.get();
            main(player, () -> beginSend(player, identity, subject, body, attach));
        });
    }

    /** 在主线程先扣除附件，再发送；失败则退回，避免“邮件已发出但物品还在”。 */
    private void beginSend(Player player, PlayerIdentity identity, String subject, String body, boolean attach) {
        if (identity.uuid().equals(player.getUniqueId().toString()) && !config.mailAllowSelf()) {
            messages.send(player, "mail.self");
            return;
        }
        ItemStack attached = null;
        if (attach) {
            if (!player.hasPermission("liuchat.mail.attach")) {
                messages.send(player, "mail.attach-no-permission");
                return;
            }
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) {
                messages.send(player, "mail.attach-empty");
                return;
            }
            attached = hand.clone();
            if (!consumeAttachment(player, attached)) {
                messages.send(player, "mail.attach-changed");
                return;
            }
        }
        final ItemStack refund = attached;
        MailDraft draft = new MailDraft(player.getUniqueId(), player.getName(),
                UUID.fromString(identity.uuid()), identity.name(), subject, body,
                attached == null ? List.of() : List.of(attached));
        mail.send(draft).thenAccept(result -> {
            if (result == MailResult.OK) {
                String shown = subject == null || subject.isBlank() ? "无主题" : subject;
                messages.send(player, "mail.sent", "${player}", identity.name(), "${subject}", shown);
                main(player, () -> openMailList(player, 1));
            } else {
                refundAttachment(player, refund);
                afterMail(player, result, "mail.send-ok", () -> openMailList(player, 1));
            }
        });
    }

    private static boolean consumeAttachment(Player player, ItemStack expected) {
        if (expected == null || expected.getType().isAir()) return false;
        ItemStack[] contents = player.getInventory().getContents();
        int need = expected.getAmount();
        int available = 0;
        for (ItemStack stack : contents) {
            if (stack != null && stack.isSimilar(expected)) available += stack.getAmount();
        }
        if (available < need) return false;
        int remaining = need;
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || !stack.isSimilar(expected)) continue;
            int take = Math.min(remaining, stack.getAmount());
            int left = stack.getAmount() - take;
            if (left <= 0) player.getInventory().setItem(i, null);
            else {
                stack.setAmount(left);
                player.getInventory().setItem(i, stack);
            }
            remaining -= take;
        }
        return remaining == 0;
    }

    private void refundAttachment(Player player, ItemStack item) {
        if (item == null) return;
        Schedulers.runFor(plugin, player, () -> {
            if (!player.isOnline()) {
                plugin.getLogger().warning("邮件发送失败，但玩家已离线，附件无法退回: " + player.getName()
                        + " x" + item.getAmount() + " " + item.getType());
                return;
            }
            var leftover = player.getInventory().addItem(item.clone());
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        });
    }

    // ---------------- 窗口与物品 ----------------

    private NormalWindow normal(Player player, String title, int rows, Map<Integer, Item> items) {
        return NormalWindow.builder()
                .setTitle(title(title))
                .setUpperPane(filledPane(rows, items))
                .setLowerPane(filledPane(4, Map.of()))
                .build(player);
    }

    private Pane filledPane(int rows, Map<Integer, Item> items) {
        Pane.Builder<?, ?> builder = Pane.builder(9, rows);
        builder.addModifier(pane -> {
            Item filler = filler();
            for (int i = 0; i < 9 * rows; i++) pane.setItem(i, filler);
            items.forEach(pane::setItem);
        });
        return builder.build();
    }

    private void open(Player player, Window window) {
        if (!player.isOnline()) {
            window.close();
            return;
        }
        Window previous = windows.put(player.getUniqueId(), window);
        window.addCloseHandler(reason -> windows.remove(player.getUniqueId(), window));
        if (previous != null && previous != window) {
            try {
                previous.close();
            } catch (RuntimeException ignored) {
                // 新窗口打开会顶替旧窗口
            }
        }
        window.open().whenComplete((result, error) -> {
            if (error != null) plugin.getLogger().warning("打开 Sparrow 界面失败: " + error.getMessage());
        });
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.4f, 1.2f);
    }

    private void nav(Map<Integer, Item> items, int page, int pages,
                     Consumer<Player> previous, Consumer<Player> next, Consumer<Player> back) {
        items.put(45, button(Material.ARROW, "&7返回", List.of("&7点击返回上一级"),
                click -> back.accept(click.player())));
        if (page > 1) {
            items.put(48, button(Material.SPECTRAL_ARROW, "&e上一页", List.of("&7第 " + (page - 1) + " 页"),
                    click -> previous.accept(click.player())));
        }
        if (page < pages) {
            items.put(50, button(Material.SPECTRAL_ARROW, "&e下一页", List.of("&7第 " + (page + 1) + " 页"),
                    click -> next.accept(click.player())));
        }
        items.put(49, button(Material.PAPER, "&7第 " + page + " / " + pages + " 页", List.of(), null));
    }

    private void openConfirm(Player player, String title, Consumer<Player> onConfirm) {
        Map<Integer, Item> items = new ConcurrentHashMap<>();
        items.put(4, button(Material.PAPER, text("&e").append(user(title)),
                List.of(text("&7此操作不可撤销")), null));
        items.put(11, button(Material.LIME_STAINED_GLASS_PANE, "&a确认", List.of("&7点击执行"),
                click -> { click.window().close(); onConfirm.accept(click.player()); }));
        items.put(15, button(Material.RED_STAINED_GLASS_PANE, "&c取消", List.of("&7返回"),
                click -> click.window().close()));
        open(player, normal(player, "确认操作", 3, items));
    }

    private static String inputText(ItemClick click, AtomicReference<String> fallback) {
        return click.window() instanceof AnvilWindow anvil ? anvil.getRenameText() : fallback.get();
    }

    private static Item filler() {
        return item(buttonStack(Material.GRAY_STAINED_GLASS_PANE, " ", List.of()), null);
    }

    private static Item button(Material material, String name, List<String> lore, Consumer<ItemClick> action) {
        return item(buttonStack(material, text(name), lore.stream().map(SparrowSocialMenuService::text).toList()), action);
    }

    private static Item button(Material material, Component name, List<Component> lore, Consumer<ItemClick> action) {
        return item(buttonStack(material, name, lore), action);
    }

    private static Item head(String name, UUID uuid, String title, List<String> lore, Consumer<ItemClick> action) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta raw = item.getItemMeta();
        if (raw instanceof SkullMeta skull) {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                skull.setOwningPlayer(online);
            } else {
                var offline = Bukkit.getOfflinePlayerIfCached(name);
                if (offline != null) skull.setOwningPlayer(offline);
            }
            item.setItemMeta(skull);
        }
        decorate(item, text("&e").append(user(title)), lore.stream().map(SparrowSocialMenuService::text).toList());
        return item(item, action);
    }

    private static Item item(ItemStack stack, Consumer<ItemClick> action) {
        ItemBuilder builder = Item.builder().setItemProviderConstant(stack);
        builder.addClickHandler(action == null ? click -> { } : action);
        return builder.build();
    }

    private static ItemStack buttonStack(Material material, String name, List<String> lore) {
        return buttonStack(material, text(name), lore.stream().map(SparrowSocialMenuService::text).toList());
    }

    private static ItemStack buttonStack(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        decorate(item, name, lore);
        return item;
    }

    private static void decorate(ItemStack item, Component name, List<Component> lore) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.displayName(name);
        if (!lore.isEmpty()) meta.lore(lore);
        item.setItemMeta(meta);
    }

    private static Component title(String value) {
        return LegacyComponentSerializer.legacySection().deserialize(TextUtil.color("&8" + value))
                .decoration(TextDecoration.ITALIC, false);
    }

    private static Component text(String value) {
        return LegacyComponentSerializer.legacySection().deserialize(TextUtil.color(value))
                .decoration(TextDecoration.ITALIC, false);
    }

    private static Component user(String value) {
        return Component.text(value == null ? "" : value).decoration(TextDecoration.ITALIC, false);
    }

    private static Component line(String coloredPrefix, String userValue) {
        return text(coloredPrefix).append(user(userValue));
    }

    private void main(Player player, Runnable action) {
        if (!plugin.isEnabled() || !player.isOnline()) return;
        Schedulers.runFor(plugin, player, () -> {
            if (plugin.isEnabled() && player.isOnline()) action.run();
        });
    }

    private void afterFriend(Player player, FriendResult result, String okKey, String target, Runnable reopen) {
        String key = switch (result) {
            case OK -> okKey;
            case ACCEPTED_MUTUAL -> "friend.mutual-accepted";
            case REQUEST_SENT -> "friend.request-sent";
            case REQUEST_EXISTS -> "friend.request-exists";
            case ALREADY_FRIENDS -> "friend.already-friends";
            case NOT_FRIENDS -> "friend.not-friends";
            case REQUEST_NOT_FOUND -> "friend.request-not-found";
            case FRIENDS_FULL -> "friend.full";
            case REQUEST_LIMIT -> "friend.request-limit";
            case SELF -> "friend.self";
            case CANCELLED -> "friend.cancelled";
            case NOT_FOUND -> "friend.not-found";
            case DISABLED -> "friend.disabled";
            default -> "friend.storage-unavailable";
        };
        messages.send(player, key, "${player}", target == null ? "" : target);
        main(player, reopen);
    }

    private void afterMail(Player player, MailResult result, String okKey, Runnable reopen) {
        String key = switch (result) {
            case OK -> okKey;
            case SELF -> "mail.self";
            case CANCELLED -> "mail.cancelled";
            case DISABLED -> "mail.disabled";
            case STORAGE_UNAVAILABLE -> "mail.storage-unavailable";
            case SUBJECT_TOO_LONG -> "mail.subject-too-long";
            case BODY_TOO_LONG -> "mail.body-too-long";
            case ATTACHMENT_TOO_MANY -> "mail.attach-too-many";
            case ATTACHMENT_TOO_LARGE -> "mail.attach-too-large";
            case ATTACHMENT_INVALID -> "mail.attach-invalid";
            case MAILBOX_FULL -> "mail.mailbox-full";
            case COOLDOWN -> "mail.cooldown";
            case NOT_FOUND -> "mail.not-found";
            case EXPIRED -> "mail.expired";
            case ALREADY_CLAIMED -> "mail.already-claimed";
            case NO_ATTACHMENTS -> "mail.no-attachments";
            case INVENTORY_FULL -> "mail.inventory-full";
            case PLAYER_OFFLINE -> "mail.player-offline";
            case SENDER_NOT_FOUND, RECIPIENT_NOT_FOUND -> "mail.not-found";
        };
        messages.send(player, key);
        main(player, reopen);
    }
}

package com.liu.liuchat.service;

import com.liu.liuchat.api.Friend;
import com.liu.liuchat.api.FriendRequest;
import com.liu.liuchat.api.FriendResult;
import com.liu.liuchat.api.Mail;
import com.liu.liuchat.api.MailDraft;
import com.liu.liuchat.api.MailPage;
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
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 好友/邮件的箱子界面。命令只负责打开界面，具体增删/领取/编写都通过点击或 Paper Dialog 完成。
 * 所有点击取消默认行为，避免从只读界面取走展示物品。
 */
public final class SocialMenuService implements Listener {
    private static final int FRIEND_ROWS = 54;
    private static final int MAIL_ROWS = 54;
    private static final int CONTENT = 45;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final JavaPlugin plugin;
    private final FriendService friends;
    private final MailService mail;
    private final PlayerDirectoryService directory;
    private final MessageManager messages;
    private final ConfigManager config;

    public SocialMenuService(JavaPlugin plugin, FriendService friends, MailService mail,
                             PlayerDirectoryService directory, MessageManager messages, ConfigManager config) {
        this.plugin = plugin;
        this.friends = friends;
        this.mail = mail;
        this.directory = directory;
        this.messages = messages;
        this.config = config;
    }

    public void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder) {
                player.closeInventory();
            }
        }
    }

    // ---------------- 好友 ----------------

    public void openFriendMain(Player player) {
        if (!friends.isEnabled()) {
            messages.send(player, "friend.disabled");
            return;
        }
        MenuHolder holder = new MenuHolder("好友");
        Inventory inventory = Bukkit.createInventory(holder, 27, title("好友"));
        holder.inventory = inventory;
        fill(inventory, 0, 27);
        inventory.setItem(10, button(Material.PLAYER_HEAD, "&b好友列表",
                List.of("&7查看好友的在线状态", "&7点击打开")));
        holder.actions.put(10, left(p -> openFriendList(p, 1)));
        inventory.setItem(12, button(Material.BELL, "&e好友请求",
                List.of("&7查看收到的好友请求", "&7左键接受 / 右键拒绝")));
        holder.actions.put(12, left(p -> openFriendRequests(p, 1)));
        inventory.setItem(14, button(Material.WRITABLE_BOOK, "&a添加好友",
                List.of("&7输入玩家名发送请求", "&7点击打开输入框")));
        holder.actions.put(14, left(this::openAddFriendDialog));
        inventory.setItem(16, button(Material.PAPER, "&7已发送的请求",
                List.of("&7查看等待对方处理的请求", "&7点击打开")));
        holder.actions.put(16, left(p -> openFriendSent(p, 1)));
        inventory.setItem(22, button(Material.BARRIER, "&c关闭", List.of("&7点击关闭界面")));
        holder.actions.put(22, left(Player::closeInventory));
        player.openInventory(inventory);
        click(player);
    }

    private void openFriendList(Player player, int page) {
        friends.friends(player.getUniqueId()).thenAccept(all -> main(player, () -> {
            MenuHolder holder = new MenuHolder("好友列表");
            Inventory inventory = Bukkit.createInventory(holder, FRIEND_ROWS, title("好友列表"));
            holder.inventory = inventory;
            fill(inventory, 0, FRIEND_ROWS);
            if (all.isEmpty()) {
                inventory.setItem(22, button(Material.COBWEB, "&7还没有好友", List.of("&7点击左下角添加好友")));
            }
            int pageSize = Math.max(1, Math.min(CONTENT, config.friendsPageSize()));
            int pages = Math.max(1, (all.size() + pageSize - 1) / pageSize);
            int current = Math.min(Math.max(1, page), pages);
            int from = (current - 1) * pageSize;
            int to = Math.min(all.size(), from + pageSize);
            for (int i = from; i < to; i++) {
                Friend friend = all.get(i);
                int slot = i - from;
                inventory.setItem(slot, head(friend.name(), friend.uuid(), "&e" + friend.name(),
                        List.of(friend.online() ? "&a在线" : "&7离线",
                                "&7成为好友: &f" + TIME.format(Instant.ofEpochMilli(friend.since())
                                        .atZone(ZoneId.systemDefault())),
                                "&7左键删除好友", "&7右键写邮件")));
                holder.actions.put(slot, new MenuAction(
                        p -> openConfirm(p, "删除好友 " + friend.name(),
                                confirm -> friends.removeName(confirm.getUniqueId(), confirm.getName(), friend.name())
                                        .thenAccept(result -> afterFriend(confirm, result, "friend.removed", friend.name(),
                                                () -> openFriendList(confirm, current)))),
                        p -> openCompose(p, friend.name())));
            }
            nav(holder, inventory, current, pages, p -> openFriendList(p, current - 1),
                    p -> openFriendList(p, current + 1), p -> openFriendMain(p));
            player.openInventory(inventory);
            click(player);
        }));
    }

    private void openFriendRequests(Player player, int page) {
        friends.incomingRequests(player.getUniqueId()).thenAccept(requests -> main(player, () -> {
            MenuHolder holder = new MenuHolder("好友请求");
            Inventory inventory = Bukkit.createInventory(holder, FRIEND_ROWS, title("好友请求"));
            holder.inventory = inventory;
            fill(inventory, 0, FRIEND_ROWS);
            if (requests.isEmpty()) {
                inventory.setItem(22, button(Material.COBWEB, "&7没有待处理请求", List.of("&7收到请求后会出现在这里")));
            }
            int pageSize = Math.max(1, Math.min(CONTENT, config.friendsPageSize()));
            int pages = Math.max(1, (requests.size() + pageSize - 1) / pageSize);
            int current = Math.min(Math.max(1, page), pages);
            int from = (current - 1) * pageSize;
            int to = Math.min(requests.size(), from + pageSize);
            for (int i = from; i < to; i++) {
                FriendRequest request = requests.get(i);
                int slot = i - from;
                inventory.setItem(slot, head(request.senderName(), request.senderId(), "&e" + request.senderName(),
                        List.of("&7请求添加你为好友",
                                "&7时间: &f" + TIME.format(Instant.ofEpochMilli(request.createdAt())
                                        .atZone(ZoneId.systemDefault())),
                                "&a左键接受", "&c右键拒绝")));
                holder.actions.put(slot, new MenuAction(
                        p -> friends.acceptName(p.getUniqueId(), p.getName(), request.senderName())
                                .thenAccept(result -> afterFriend(p, result, "friend.accepted", request.senderName(),
                                        () -> openFriendRequests(p, current))),
                        p -> friends.denyName(p.getUniqueId(), p.getName(), request.senderName())
                                .thenAccept(result -> afterFriend(p, result, "friend.denied", request.senderName(),
                                        () -> openFriendRequests(p, current)))));
            }
            nav(holder, inventory, current, pages, p -> openFriendRequests(p, current - 1),
                    p -> openFriendRequests(p, current + 1), p -> openFriendMain(p));
            player.openInventory(inventory);
            click(player);
        }));
    }

    private void openFriendSent(Player player, int page) {
        friends.outgoingRequests(player.getUniqueId()).thenAccept(requests -> main(player, () -> {
            MenuHolder holder = new MenuHolder("已发送请求");
            Inventory inventory = Bukkit.createInventory(holder, FRIEND_ROWS, title("已发送的请求"));
            holder.inventory = inventory;
            fill(inventory, 0, FRIEND_ROWS);
            if (requests.isEmpty()) {
                inventory.setItem(22, button(Material.COBWEB, "&7没有已发送请求", List.of("&7添加好友后会出现在这里")));
            }
            int pageSize = Math.max(1, Math.min(CONTENT, config.friendsPageSize()));
            int pages = Math.max(1, (requests.size() + pageSize - 1) / pageSize);
            int current = Math.min(Math.max(1, page), pages);
            int from = (current - 1) * pageSize;
            int to = Math.min(requests.size(), from + pageSize);
            for (int i = from; i < to; i++) {
                FriendRequest request = requests.get(i);
                int slot = i - from;
                inventory.setItem(slot, head(request.targetName(), request.targetId(), "&e" + request.targetName(),
                        List.of("&7等待对方处理", "&7左键撤回请求")));
                holder.actions.put(slot, left(p -> friends.cancelName(p.getUniqueId(), p.getName(),
                        request.targetName()).thenAccept(result -> afterFriend(p, result, "friend.cancelled",
                        request.targetName(), () -> openFriendSent(p, current)))));
            }
            nav(holder, inventory, current, pages, p -> openFriendSent(p, current - 1),
                    p -> openFriendSent(p, current + 1), p -> openFriendMain(p));
            player.openInventory(inventory);
            click(player);
        }));
    }

    private void openAddFriendDialog(Player player) {
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(text("添加好友"))
                        .inputs(List.of(DialogInput.text("name", text("玩家名"))
                                .maxLength(32).build())).build())
                .type(DialogType.multiAction(List.of(
                        ActionButton.create(text("发送请求"), text("向该玩家发送好友请求"), 160,
                                DialogAction.customClick((view, audience) -> main(player, () -> {
                                    String name = view.getText("name");
                                    if (name == null || name.isBlank()) {
                                        messages.send(player, "friend.not-found", "${player}", "");
                                        return;
                                    }
                                    directory.find(name.strip()).thenAccept(target -> {
                                        if (target.isEmpty()) {
                                            messages.send(player, "friend.not-found", "${player}", name);
                                            return;
                                        }
                                        PlayerIdentity identity = target.get();
                                        friends.request(player.getUniqueId(), player.getName(),
                                                        UUID.fromString(identity.uuid()), identity.name())
                                                .thenAccept(result -> afterFriend(player, result, "friend.ok",
                                                        identity.name(), () -> openFriendMain(player)));
                                    });
                                }), ClickCallback.Options.builder().uses(1).build()))),
                        ActionButton.create(text("取消"), Component.empty(), 100,
                                DialogAction.customClick((view, audience) -> { },
                                        ClickCallback.Options.builder().uses(1).build())), 1)));
        player.showDialog(dialog);
    }

    // ---------------- 邮件 ----------------

    public void openMailList(Player player) {
        openMailList(player, 1);
    }

    private void openMailList(Player player, int page) {
        if (!mail.isEnabled()) {
            messages.send(player, "mail.disabled");
            return;
        }
        mail.inbox(player.getUniqueId(), page).thenAccept(result -> main(player, () -> {
            MenuHolder holder = new MenuHolder("邮箱");
            Inventory inventory = Bukkit.createInventory(holder, MAIL_ROWS, title("邮箱"));
            holder.inventory = inventory;
            fill(inventory, 0, MAIL_ROWS);
            if (result.total() == 0) {
                inventory.setItem(22, button(Material.COBWEB, "&7邮箱是空的", List.of("&7点击右下角写邮件")));
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
                inventory.setItem(i, button(entry.read() ? Material.BOOK : Material.WRITTEN_BOOK,
                        text("&e").append(user(entry.subject())), lore));
                holder.actions.put(i, new MenuAction(
                        p -> openMailDetail(p, entry.id()),
                        p -> openConfirm(p, "删除邮件 " + entry.subject(),
                                confirm -> mail.delete(confirm.getUniqueId(), entry.id())
                                        .thenAccept(result1 -> afterMail(confirm, result1, "mail.delete-ok",
                                                () -> openMailList(confirm, current))))));
            }
            nav(holder, inventory, current, pages, p -> openMailList(p, current - 1),
                    p -> openMailList(p, current + 1), Player::closeInventory);
            inventory.setItem(53, button(Material.WRITABLE_BOOK, "&a写邮件",
                    List.of("&7输入收件人、主题与正文", "&7可附带主手物品")));
            holder.actions.put(53, left(this::openCompose));
            player.openInventory(inventory);
            click(player);
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
        MenuHolder holder = new MenuHolder("邮件");
        Inventory inventory = Bukkit.createInventory(holder, MAIL_ROWS, title("邮件"));
        holder.inventory = inventory;
        fill(inventory, 0, MAIL_ROWS);

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
        inventory.setItem(4, button(Material.PAPER, text("&e").append(user(entry.subject())), info));

        if (!entry.claimed()) {
            int slot = 18;
            for (ItemStack item : entry.attachments()) {
                if (slot >= 27) break;
                inventory.setItem(slot, item);
                holder.actions.put(slot, left(p -> claimMail(p, entry.id())));
                slot++;
            }
        }
        inventory.setItem(45, button(Material.ARROW, "&7返回邮箱", List.of("&7点击返回")));
        holder.actions.put(45, left(p -> openMailList(p, 1)));
        if (!entry.attachments().isEmpty() && !entry.claimed()) {
            inventory.setItem(49, button(Material.CHEST, "&a领取附件",
                    List.of("&7把全部附件放入背包", "&7背包不足时不会领取")));
            holder.actions.put(49, left(p -> claimMail(p, entry.id())));
        } else {
            inventory.setItem(49, button(entry.claimed() ? Material.CHEST : Material.BARRIER,
                    entry.claimed() ? "&7附件已领取" : "&7没有附件",
                    List.of(entry.claimed() ? "&7不能重复领取" : "&7这封邮件只有正文")));
        }
        inventory.setItem(53, button(Material.BARRIER, "&c删除邮件", List.of("&7点击后需要确认")));
        holder.actions.put(53, left(p -> openConfirm(p, "删除邮件 " + entry.subject(),
                confirm -> mail.delete(confirm.getUniqueId(), entry.id())
                        .thenAccept(result -> afterMail(confirm, result, "mail.delete-ok",
                                () -> openMailList(confirm, 1))))));
        player.openInventory(inventory);
        click(player);
    }

    private void claimMail(Player player, String mailId) {
        mail.claim(player, mailId).thenAccept(result -> afterMail(player, result, "mail.claim-ok",
                () -> openMailDetail(player, mailId)));
    }

    private void openCompose(Player player) {
        openCompose(player, "");
    }

    private void openCompose(Player player, String initialRecipient) {
        TextDialogInput.Builder body = DialogInput.text("body", text("正文"))
                .maxLength(config.mailMaxBody())
                .multiline(TextDialogInput.MultilineOptions.create(10, 120));
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(text("写邮件"))
                        .inputs(List.of(
                                DialogInput.text("recipient", text("收件人"))
                                        .initial(initialRecipient).maxLength(32).build(),
                                DialogInput.text("subject", text("主题"))
                                        .maxLength(config.mailMaxSubject()).build(),
                                body.build(),
                                DialogInput.bool("attach", text("附带主手物品"), false, "是", "否")))
                        .build())
                .type(DialogType.multiAction(List.of(
                        ActionButton.create(text("发送"), text("发送这封邮件"), 160,
                                DialogAction.customClick((view, audience) -> main(player, () -> sendFromDialog(player, view)),
                                        ClickCallback.Options.builder().uses(1).build()))),
                        ActionButton.create(text("取消"), Component.empty(), 100,
                                DialogAction.customClick((view, audience) -> { },
                                        ClickCallback.Options.builder().uses(1).build())), 1)));
        player.showDialog(dialog);
    }

    private void sendFromDialog(Player player, DialogResponseView view) {
        String recipient = view.getText("recipient");
        if (recipient == null || recipient.isBlank()) {
            messages.send(player, "mail.player-not-found", "${player}", "");
            return;
        }
        String subject = view.getText("subject");
        String body = view.getText("body");
        boolean attach = Boolean.TRUE.equals(view.getBoolean("attach"));
        List<ItemStack> attachments = List.of();
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
            attachments = List.of(hand.clone());
        }
        final List<ItemStack> finalAttachments = attachments;
        directory.find(recipient.strip()).thenAccept(target -> {
            if (target.isEmpty()) {
                messages.send(player, "mail.player-not-found", "${player}", recipient);
                return;
            }
            PlayerIdentity identity = target.get();
            if (identity.uuid().equals(player.getUniqueId().toString()) && !config.mailAllowSelf()) {
                messages.send(player, "mail.self");
                return;
            }
            MailDraft draft = new MailDraft(player.getUniqueId(), player.getName(),
                    UUID.fromString(identity.uuid()), identity.name(), subject, body, finalAttachments);
            mail.send(draft).thenAccept(result -> {
                if (result == MailResult.OK) {
                    String shown = subject == null || subject.isBlank() ? "无主题" : subject;
                    messages.send(player, "mail.sent", "${player}", identity.name(), "${subject}", shown);
                    main(player, () -> openMailList(player, 1));
                } else {
                    afterMail(player, result, "mail.send-ok", () -> openMailList(player, 1));
                }
            });
        });
    }

    // ---------------- 确认与反馈 ----------------

    private void openConfirm(Player player, String title, Consumer<Player> onConfirm) {
        MenuHolder holder = new MenuHolder("确认");
        Inventory inventory = Bukkit.createInventory(holder, 27, title("确认操作"));
        holder.inventory = inventory;
        fill(inventory, 0, 27);
        inventory.setItem(4, button(Material.PAPER, text("&e").append(user(title)),
                List.of(text("&7此操作不可撤销"))));
        inventory.setItem(11, button(Material.LIME_STAINED_GLASS_PANE, "&a确认", List.of("&7点击执行")));
        holder.actions.put(11, left(p -> { p.closeInventory(); onConfirm.accept(p); }));
        inventory.setItem(15, button(Material.RED_STAINED_GLASS_PANE, "&c取消", List.of("&7返回")));
        holder.actions.put(15, left(Player::closeInventory));
        player.openInventory(inventory);
        click(player);
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

    // ---------------- 点击 ----------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= top.getSize()) return;
        MenuAction action = holder.actions.get(slot);
        if (action == null) return;
        Consumer<Player> run = event.isRightClick() ? action.right() : action.left();
        if (run == null) return;
        Schedulers.runFor(plugin, player, () -> {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            run.accept(player);
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) event.setCancelled(true);
    }

    // ---------------- 渲染 ----------------

    private static void fill(Inventory inventory, int from, int to) {
        ItemStack filler = button(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = from; i < to; i++) inventory.setItem(i, filler);
    }

    private static void nav(MenuHolder holder, Inventory inventory, int page, int pages,
                            Consumer<Player> previous, Consumer<Player> next, Consumer<Player> back) {
        inventory.setItem(45, button(Material.ARROW, "&7返回", List.of("&7点击返回上一级")));
        holder.actions.put(45, left(back));
        if (page > 1) {
            inventory.setItem(48, button(Material.SPECTRAL_ARROW, "&e上一页", List.of("&7第 " + (page - 1) + " 页")));
            holder.actions.put(48, left(previous));
        }
        if (page < pages) {
            inventory.setItem(50, button(Material.SPECTRAL_ARROW, "&e下一页", List.of("&7第 " + (page + 1) + " 页")));
            holder.actions.put(50, left(next));
        }
        inventory.setItem(49, button(Material.PAPER, "&7第 " + page + " / " + pages + " 页", List.of()));
    }

    private static ItemStack button(Material material, String name, List<String> lore) {
        return button(material, text(name), lore.stream().map(SocialMenuService::text).toList());
    }

    private static ItemStack button(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        decorate(item, name, lore);
        return item;
    }

    private static ItemStack head(String name, UUID uuid, String title, List<String> lore) {
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
        decorate(item, title, lore);
        return item;
    }

    private static void decorate(ItemStack item, String name, List<String> lore) {
        decorate(item, text(name), lore.stream().map(SocialMenuService::text).toList());
    }

    private static void decorate(ItemStack item, Component name, List<Component> lore) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.displayName(name);
        if (!lore.isEmpty()) meta.lore(lore);
        item.setItemMeta(meta);
    }

    private static String title(String value) {
        return TextUtil.color("&8" + value);
    }

    private static Component text(String value) {
        return LegacyComponentSerializer.legacySection().deserialize(TextUtil.color(value))
                .decoration(TextDecoration.ITALIC, false);
    }

    /** 玩家可控文本：不经过颜色解析，避免主题/正文注入颜色或 MiniMessage。 */
    private static Component user(String value) {
        return Component.text(value == null ? "" : value).decoration(TextDecoration.ITALIC, false);
    }

    private static Component line(String coloredPrefix, String userValue) {
        return text(coloredPrefix).append(user(userValue));
    }

    private static void click(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.4f, 1.2f);
    }

    private void main(Player player, Runnable action) {
        if (!plugin.isEnabled() || !player.isOnline()) return;
        Schedulers.runFor(plugin, player, () -> {
            if (plugin.isEnabled() && player.isOnline()) action.run();
        });
    }

    private record MenuAction(Consumer<Player> left, Consumer<Player> right) { }

    private static MenuAction left(Consumer<Player> action) {
        return new MenuAction(action, null);
    }

    private static final class MenuHolder implements InventoryHolder {
        private final String name;
        private Inventory inventory;
        private final Map<Integer, MenuAction> actions = new HashMap<>();

        private MenuHolder(String name) {
            this.name = name;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private static Component text(String value) {
            return LegacyComponentSerializer.legacySection().deserialize(TextUtil.color(value))
                    .decoration(TextDecoration.ITALIC, false);
        }
    }
}

package com.liu.liuchat.service;

import com.liu.liuchat.api.Mail;
import com.liu.liuchat.api.MailApi;
import com.liu.liuchat.api.MailDraft;
import com.liu.liuchat.api.MailPage;
import com.liu.liuchat.api.MailResult;
import com.liu.liuchat.api.event.MailClaimEvent;
import com.liu.liuchat.api.event.MailDeleteEvent;
import com.liu.liuchat.api.event.MailReadEvent;
import com.liu.liuchat.api.event.MailReceivedEvent;
import com.liu.liuchat.api.event.MailSendEvent;
import com.liu.liuchat.config.ConfigManager;
import com.liu.liuchat.config.MessageManager;
import com.liu.liuchat.model.MailRow;
import com.liu.liuchat.storage.Database;
import com.liu.liuchat.util.Schedulers;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * 邮件服务：离线收件、正文、持久化物品附件、领取/已读/删除、过期清理与 API。
 * 数据库操作在专用线程；领取附件切回玩家区域线程后再操作背包。
 */
public final class MailService implements MailApi, Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final Database database;
    private final ConfigManager config;
    private final PlayerDirectoryService directory;
    private final SocialNotifier notifier;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "LiuChat-mail");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Long> lastSend = new ConcurrentHashMap<>();
    private Schedulers.Handle purgeTask;
    private volatile boolean closed;

    public MailService(JavaPlugin plugin, Database database, ConfigManager config, MessageManager messages,
                       CrossServerService crossServer, PlayerDirectoryService directory) {
        this.plugin = plugin;
        this.database = database;
        this.config = config;
        this.directory = directory;
        this.notifier = new SocialNotifier(plugin, messages, crossServer);
    }

    public void start() {
        purgeTask = Schedulers.runAsyncTimer(plugin, () -> {
            if (config.mailEnabled() && database.isReady()) {
                database.deleteExpiredMail(System.currentTimeMillis());
            }
        }, 1200L, 72_000L);
    }

    @Override
    public boolean isEnabled() {
        return !closed && config.mailEnabled();
    }

    @Override
    public CompletableFuture<MailResult> send(MailDraft draft) {
        if (!isEnabled()) return CompletableFuture.completedFuture(MailResult.DISABLED);
        if (draft == null || draft.senderId() == null || draft.recipientId() == null) {
            return CompletableFuture.completedFuture(MailResult.NOT_FOUND);
        }
        if (draft.senderId().equals(draft.recipientId()) && !config.mailAllowSelf()) {
            return CompletableFuture.completedFuture(MailResult.SELF);
        }
        if (!database.isReady()) return CompletableFuture.completedFuture(MailResult.STORAGE_UNAVAILABLE);
        String subject = normalizeSubject(draft.subject());
        String body = draft.body() == null ? "" : draft.body().strip();
        if (subject.length() > config.mailMaxSubject()) {
            return CompletableFuture.completedFuture(MailResult.SUBJECT_TOO_LONG);
        }
        if (body.length() > config.mailMaxBody()) {
            return CompletableFuture.completedFuture(MailResult.BODY_TOO_LONG);
        }
        List<ItemStack> attachments = new ArrayList<>();
        for (ItemStack item : draft.attachments()) {
            if (item == null || item.getType().isAir()) return CompletableFuture.completedFuture(MailResult.ATTACHMENT_INVALID);
            attachments.add(item.clone());
        }
        if (attachments.size() > config.mailMaxAttachments()) {
            return CompletableFuture.completedFuture(MailResult.ATTACHMENT_TOO_MANY);
        }
        if (cooling(draft.senderId())) {
            return CompletableFuture.completedFuture(MailResult.COOLDOWN);
        }
        String senderName = display(draft.senderName(), draft.senderId());
        String recipientName = display(draft.recipientName(), draft.recipientId());
        MailSendEvent event = new MailSendEvent(new MailDraft(draft.senderId(), senderName,
                draft.recipientId(), recipientName, subject, body, attachments));
        SocialEvents.fire(plugin, event);
        if (event.isCancelled()) return CompletableFuture.completedFuture(MailResult.CANCELLED);
        return CompletableFuture.supplyAsync(() -> sendNow(draft.senderId(), senderName,
                draft.recipientId(), recipientName, subject, body, attachments), worker)
                .exceptionally(error -> {
                    plugin.getLogger().log(Level.WARNING, "发送邮件失败", error);
                    return MailResult.STORAGE_UNAVAILABLE;
                });
    }

    private MailResult sendNow(UUID senderId, String senderName, UUID recipientId, String recipientName,
                               String subject, String body, List<ItemStack> attachments) {
        if (!database.isReady()) return MailResult.STORAGE_UNAVAILABLE;
        int max = config.mailMaxPerPlayer();
        if (max > 0 && database.countMail(recipientId.toString(), System.currentTimeMillis()) >= max) {
            return MailResult.MAILBOX_FULL;
        }
        List<String> encoded = new ArrayList<>();
        for (ItemStack item : attachments) {
            byte[] bytes;
            try {
                bytes = ItemSerializer.bytes(item);
            } catch (RuntimeException error) {
                return MailResult.ATTACHMENT_INVALID;
            }
            if (bytes == null) return MailResult.ATTACHMENT_INVALID;
            if (bytes.length > config.mailMaxAttachmentBytes()) return MailResult.ATTACHMENT_TOO_LARGE;
            encoded.add(ItemSerializer.encode(bytes));
        }
        long now = System.currentTimeMillis();
        int days = config.mailExpireDays();
        long expires = days <= 0 ? 0 : now + days * 86_400_000L;
        MailRow row = new MailRow(UUID.randomUUID().toString(), senderId.toString(), senderName,
                recipientId.toString(), recipientName, subject, body, encoded, now, expires, false, false);
        if (!database.saveMail(row)) return MailResult.STORAGE_UNAVAILABLE;
        Mail mail = toApi(row);
        SocialEvents.fire(plugin, new MailReceivedEvent(mail));
        notifier.sendClickable(recipientName, "mail.received-notify", "/liuc mail",
                "${player}", senderName, "${subject}", subject);
        return MailResult.OK;
    }

    /** 原子预占发送冷却，避免并发 API 调用同时通过检查。失败/取消也计入冷却。 */
    private synchronized boolean cooling(UUID sender) {
        int seconds = config.mailSendCooldownSeconds();
        if (seconds <= 0) return false;
        long now = System.currentTimeMillis();
        Long last = lastSend.get(sender.toString());
        if (last != null && now - last < seconds * 1000L) return true;
        lastSend.put(sender.toString(), now);
        return false;
    }

    @Override
    public CompletableFuture<MailPage> inbox(UUID recipient, int page) {
        if (!isEnabled() || recipient == null) {
            return CompletableFuture.completedFuture(new MailPage(List.of(), 1, config.mailPageSize(), 0, 0));
        }
        int requested = Math.max(1, Math.min(10_000, page));
        int pageSize = config.mailPageSize();
        return CompletableFuture.supplyAsync(() -> {
            long now = System.currentTimeMillis();
            String uuid = recipient.toString();
            int total = database.countMail(uuid, now);
            int unread = database.countUnread(uuid, now);
            List<MailRow> rows = database.loadMail(uuid, now, (requested - 1) * pageSize, pageSize);
            List<Mail> mails = rows == null ? List.of() : rows.stream().map(this::toApi).toList();
            return new MailPage(mails, requested, pageSize, total, unread);
        }, worker).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "读取邮箱失败", error);
            return new MailPage(List.of(), requested, pageSize, 0, 0);
        });
    }

    @Override
    public CompletableFuture<Optional<Mail>> mail(UUID recipient, String idOrPrefix) {
        if (!isEnabled() || recipient == null) return CompletableFuture.completedFuture(Optional.<Mail>empty());
        return CompletableFuture.supplyAsync(() -> {
            MailRow row = row(recipient, idOrPrefix);
            if (row == null || row.expired(System.currentTimeMillis())) return Optional.<Mail>empty();
            return Optional.of(toApi(row));
        }, worker).exceptionally(error -> Optional.<Mail>empty());
    }

    @Override
    public CompletableFuture<Integer> unread(UUID recipient) {
        if (!isEnabled() || recipient == null) return CompletableFuture.completedFuture(0);
        return CompletableFuture.supplyAsync(() -> database.countUnread(recipient.toString(),
                System.currentTimeMillis()), worker).exceptionally(error -> 0);
    }

    @Override
    public CompletableFuture<MailResult> markRead(UUID recipient, String idOrPrefix) {
        if (!isEnabled()) return CompletableFuture.completedFuture(MailResult.DISABLED);
        if (!database.isReady()) return CompletableFuture.completedFuture(MailResult.STORAGE_UNAVAILABLE);
        return CompletableFuture.supplyAsync(() -> {
            MailRow row = row(recipient, idOrPrefix);
            if (row == null) return MailResult.NOT_FOUND;
            if (row.expired(System.currentTimeMillis())) return MailResult.EXPIRED;
            if (row.read()) return MailResult.OK;
            boolean changed = database.markMailRead(row.id(), recipient.toString());
            if (changed) SocialEvents.fire(plugin, new MailReadEvent(recipient, row.id()));
            return changed ? MailResult.OK : MailResult.NOT_FOUND;
        }, worker).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "标记邮件已读失败", error);
            return MailResult.STORAGE_UNAVAILABLE;
        });
    }

    @Override
    public CompletableFuture<MailResult> claim(Player player, String idOrPrefix) {
        if (!isEnabled()) return CompletableFuture.completedFuture(MailResult.DISABLED);
        if (player == null || !player.isOnline()) return CompletableFuture.completedFuture(MailResult.PLAYER_OFFLINE);
        if (!database.isReady()) return CompletableFuture.completedFuture(MailResult.STORAGE_UNAVAILABLE);
        CompletableFuture<MailResult> result = new CompletableFuture<>();
        UUID recipient = player.getUniqueId();
        CompletableFuture.supplyAsync(() -> row(recipient, idOrPrefix), worker)
                .thenAccept(row -> {
                    if (row == null) {
                        result.complete(MailResult.NOT_FOUND);
                    } else if (row.expired(System.currentTimeMillis())) {
                        result.complete(MailResult.EXPIRED);
                    } else if (row.claimed()) {
                        result.complete(MailResult.ALREADY_CLAIMED);
                    } else if (row.attachments().isEmpty()) {
                        result.complete(MailResult.NO_ATTACHMENTS);
                    } else {
                        Schedulers.runFor(plugin, player, () -> claimOnPlayer(player, row, result));
                    }
                }).exceptionally(error -> {
                    plugin.getLogger().log(Level.WARNING, "读取待领取邮件失败", error);
                    result.complete(MailResult.STORAGE_UNAVAILABLE);
                    return null;
                });
        return result.completeOnTimeout(MailResult.PLAYER_OFFLINE, 10, TimeUnit.SECONDS);
    }

    private void claimOnPlayer(Player player, MailRow row, CompletableFuture<MailResult> result) {
        if (!player.isOnline()) {
            result.complete(MailResult.PLAYER_OFFLINE);
            return;
        }
        if (row.expired(System.currentTimeMillis())) {
            result.complete(MailResult.EXPIRED);
            return;
        }
        if (row.claimed()) {
            result.complete(MailResult.ALREADY_CLAIMED);
            return;
        }
        List<ItemStack> items = new ArrayList<>();
        for (String encoded : row.attachments()) {
            ItemStack item = ItemSerializer.decode(encoded);
            if (item == null) {
                result.complete(MailResult.ATTACHMENT_INVALID);
                return;
            }
            items.add(item);
        }
        if (items.isEmpty()) {
            result.complete(MailResult.NO_ATTACHMENTS);
            return;
        }
        Inventory test = Bukkit.createInventory(null, 36);
        test.setContents(player.getInventory().getStorageContents());
        Map<Integer, ItemStack> leftover = test.addItem(items.toArray(new ItemStack[0]));
        if (!leftover.isEmpty()) {
            result.complete(MailResult.INVENTORY_FULL);
            return;
        }
        final Mail mail;
        try {
            mail = toApi(row);
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "解析邮件附件失败", error);
            result.complete(MailResult.ATTACHMENT_INVALID);
            return;
        }
        MailClaimEvent event = new MailClaimEvent(player, mail);
        SocialEvents.fire(plugin, event);
        if (event.isCancelled()) {
            result.complete(MailResult.CANCELLED);
            return;
        }
        CompletableFuture.supplyAsync(() -> database.markMailClaimed(row.id(), player.getUniqueId().toString()),
                worker).thenAccept(marked -> {
                    if (!marked) {
                        result.complete(MailResult.ALREADY_CLAIMED);
                        return;
                    }
                    Schedulers.runFor(plugin, player, () -> {
                        if (!player.isOnline()) {
                            result.complete(MailResult.PLAYER_OFFLINE);
                            return;
                        }
                        Map<Integer, ItemStack> overflow = player.getInventory().addItem(items.toArray(new ItemStack[0]));
                        if (!overflow.isEmpty()) {
                            // 容量检查后被其他操作占用：掉落而不是吞掉，且邮件已标记领取，避免复制
                            for (ItemStack item : overflow.values()) {
                                player.getWorld().dropItemNaturally(player.getLocation(), item);
                            }
                            plugin.getLogger().warning("玩家 " + player.getName()
                                    + " 领取邮件附件时背包已满，剩余物品已掉落在脚下");
                        }
                        result.complete(MailResult.OK);
                    });
                }).exceptionally(error -> {
                    plugin.getLogger().log(Level.WARNING, "标记邮件附件已领取失败", error);
                    result.complete(MailResult.STORAGE_UNAVAILABLE);
                    return null;
                });
    }

    @Override
    public CompletableFuture<MailResult> delete(UUID recipient, String idOrPrefix) {
        if (!isEnabled()) return CompletableFuture.completedFuture(MailResult.DISABLED);
        if (!database.isReady()) return CompletableFuture.completedFuture(MailResult.STORAGE_UNAVAILABLE);
        return CompletableFuture.supplyAsync(() -> {
            MailRow row = row(recipient, idOrPrefix);
            if (row == null) return MailResult.NOT_FOUND;
            boolean deleted = database.deleteMail(row.id(), recipient.toString());
            if (deleted) SocialEvents.fire(plugin, new MailDeleteEvent(recipient, row.id()));
            return deleted ? MailResult.OK : MailResult.NOT_FOUND;
        }, worker).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "删除邮件失败", error);
            return MailResult.STORAGE_UNAVAILABLE;
        });
    }

    private MailRow row(UUID recipient, String idOrPrefix) {
        if (recipient == null || idOrPrefix == null) return null;
        String token = idOrPrefix.strip();
        if (token.length() == 36) {
            MailRow row = database.loadMail(token);
            return row != null && recipient.toString().equals(row.recipientUuid()) ? row : null;
        }
        if (!token.matches("[0-9a-fA-F-]{4,35}")) return null;
        return database.loadMailByPrefix(recipient.toString(), token.toLowerCase(Locale.ROOT));
    }

    private Mail toApi(MailRow row) {
        List<ItemStack> attachments = new ArrayList<>();
        for (String encoded : row.attachments()) {
            ItemStack item = ItemSerializer.decode(encoded);
            if (item != null) attachments.add(item);
        }
        UUID sender = UUID.fromString(row.senderUuid());
        UUID recipient = UUID.fromString(row.recipientUuid());
        return new Mail(row.id(), sender, directory.nameOf(sender, row.senderName()),
                recipient, directory.nameOf(recipient, row.recipientName()), row.subject(), row.body(),
                attachments, row.createdAt(), row.expiresAt(), row.read(), row.claimed());
    }

    private String normalizeSubject(String subject) {
        String value = subject == null ? "" : subject.strip();
        return value.isEmpty() ? "无主题" : value;
    }

    private String display(String name, UUID uuid) {
        return directory.nameOf(uuid, name);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!isEnabled() || !config.mailNotifyOnJoin() || !database.isReady()) return;
        UUID uuid = event.getPlayer().getUniqueId();
        String name = event.getPlayer().getName();
        CompletableFuture.supplyAsync(() -> database.countUnread(uuid.toString(), System.currentTimeMillis()), worker)
                .thenAccept(count -> {
                    if (count != null && count > 0) {
                        notifier.sendClickable(name, "mail.join-unread", "/liuc mail", "${count}", String.valueOf(count));
                    }
                });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastSend.remove(event.getPlayer().getUniqueId().toString());
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (purgeTask != null) purgeTask.cancel();
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
    }
}

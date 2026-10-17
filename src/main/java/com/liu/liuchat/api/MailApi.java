package com.liu.liuchat.api;

import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 邮件系统公开 API。通过 Bukkit {@code ServicesManager} 获取。
 * <p>
 * 查询/发送返回 {@link CompletableFuture}，不阻塞主线程；{@link #claim(Player, String)}
 * 会切回玩家所在区域线程再操作背包，因此必须传入在线玩家。
 * 邮件正文与附件持久化在 SQLite/MySQL，跨子服共享（MySQL 时）。
 */
public interface MailApi {

    CompletableFuture<MailResult> send(MailDraft draft);

    /** @param page 从 1 开始 */
    CompletableFuture<MailPage> inbox(UUID recipient, int page);

    /**
     * 按完整 ID 或唯一前缀查找邮件；只能查收件人自己的邮箱。
     */
    CompletableFuture<Optional<Mail>> mail(UUID recipient, String idOrPrefix);

    CompletableFuture<Integer> unread(UUID recipient);

    CompletableFuture<MailResult> markRead(UUID recipient, String idOrPrefix);

    /** 领取附件；背包放不下时不会部分领取。 */
    CompletableFuture<MailResult> claim(Player player, String idOrPrefix);

    CompletableFuture<MailResult> delete(UUID recipient, String idOrPrefix);

    boolean isEnabled();
}

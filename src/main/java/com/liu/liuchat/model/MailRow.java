package com.liu.liuchat.model;

import java.util.List;

/**
 * 邮件数据库行。附件以 Base64 的 {@code ItemStack.serializeAsBytes()} 列表保存，
 * 不把 Bukkit ItemStack 直接放进存储层。
 */
public record MailRow(String id,
                      String senderUuid, String senderName,
                      String recipientUuid, String recipientName,
                      String subject, String body,
                      List<String> attachments,
                      long createdAt, long expiresAt,
                      boolean read, boolean claimed,
                      long readAt, long claimedAt, boolean recalled) {

    public MailRow {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    /** 兼容旧调用：没有阅读/领取时间与撤回状态。 */
    public MailRow(String id, String senderUuid, String senderName, String recipientUuid,
                   String recipientName, String subject, String body, List<String> attachments,
                   long createdAt, long expiresAt, boolean read, boolean claimed) {
        this(id, senderUuid, senderName, recipientUuid, recipientName, subject, body, attachments,
                createdAt, expiresAt, read, claimed, 0, 0, false);
    }

    public boolean expired(long now) {
        return expiresAt > 0 && expiresAt <= now;
    }

    public MailRow asRead() {
        long now = System.currentTimeMillis();
        return new MailRow(id, senderUuid, senderName, recipientUuid, recipientName, subject, body,
                attachments, createdAt, expiresAt, true, claimed,
                readAt > 0 ? readAt : now, claimedAt, recalled);
    }

    public MailRow asClaimed() {
        long now = System.currentTimeMillis();
        return new MailRow(id, senderUuid, senderName, recipientUuid, recipientName, subject, body,
                attachments, createdAt, expiresAt, read, true,
                readAt, claimedAt > 0 ? claimedAt : now, recalled);
    }
}

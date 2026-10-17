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
                      boolean read, boolean claimed) {

    public MailRow {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public boolean expired(long now) {
        return expiresAt > 0 && expiresAt <= now;
    }

    public MailRow asRead() {
        return new MailRow(id, senderUuid, senderName, recipientUuid, recipientName, subject, body,
                attachments, createdAt, expiresAt, true, claimed);
    }

    public MailRow asClaimed() {
        return new MailRow(id, senderUuid, senderName, recipientUuid, recipientName, subject, body,
                attachments, createdAt, expiresAt, read, true);
    }
}

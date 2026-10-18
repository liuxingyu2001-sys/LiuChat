package com.liu.liuchat.api;

import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/** 一封已持久化的邮件。附件只在领取前可领取一次。 */
public record Mail(String id,
                   UUID senderId, String senderName,
                   UUID recipientId, String recipientName,
                   String subject, String body,
                   List<ItemStack> attachments,
                   long createdAt, long expiresAt,
                   boolean read, boolean claimed,
                   long readAt, long claimedAt, boolean recalled) {

    public Mail {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    /** 兼容旧调用：没有阅读/领取时间与撤回状态。 */
    public Mail(String id, UUID senderId, String senderName, UUID recipientId, String recipientName,
                String subject, String body, List<ItemStack> attachments,
                long createdAt, long expiresAt, boolean read, boolean claimed) {
        this(id, senderId, senderName, recipientId, recipientName, subject, body, attachments,
                createdAt, expiresAt, read, claimed, 0, 0, false);
    }

    public boolean hasAttachments() {
        return !attachments.isEmpty();
    }

    public boolean expired(long now) {
        return expiresAt > 0 && expiresAt <= now;
    }
}

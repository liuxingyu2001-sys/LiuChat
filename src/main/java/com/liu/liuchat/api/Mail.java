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
                   boolean read, boolean claimed) {

    public Mail {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public boolean hasAttachments() {
        return !attachments.isEmpty();
    }

    public boolean expired(long now) {
        return expiresAt > 0 && expiresAt <= now;
    }
}

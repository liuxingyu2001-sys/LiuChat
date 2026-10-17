package com.liu.liuchat.api;

import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/** 发送邮件的草稿；附件会被序列化后持久化，领取时才还原为 ItemStack。 */
public record MailDraft(UUID senderId, String senderName,
                        UUID recipientId, String recipientName,
                        String subject, String body,
                        List<ItemStack> attachments) {

    public MailDraft {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
}

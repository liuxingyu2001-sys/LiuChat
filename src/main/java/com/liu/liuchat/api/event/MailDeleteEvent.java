package com.liu.liuchat.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** 邮件被某一方删除后触发；{@link #getRecipientId()} 是执行删除的一方（收件人或发件人）。 */
public final class MailDeleteEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID recipientId;
    private final String mailId;

    public MailDeleteEvent(UUID recipientId, String mailId) {
        this.recipientId = recipientId;
        this.mailId = mailId;
    }

    public UUID getRecipientId() { return recipientId; }
    public String getMailId() { return mailId; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}

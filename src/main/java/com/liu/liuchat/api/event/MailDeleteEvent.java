package com.liu.liuchat.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** 邮件被删除后触发。 */
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

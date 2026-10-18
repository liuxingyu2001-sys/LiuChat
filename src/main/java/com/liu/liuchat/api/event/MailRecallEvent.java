package com.liu.liuchat.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** 发件人成功撤回一封未读邮件后触发。 */
public final class MailRecallEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID senderId;
    private final String mailId;

    public MailRecallEvent(UUID senderId, String mailId) {
        this.senderId = senderId;
        this.mailId = mailId;
    }

    public UUID getSenderId() { return senderId; }
    public String getMailId() { return mailId; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}

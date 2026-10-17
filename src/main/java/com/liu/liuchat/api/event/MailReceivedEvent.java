package com.liu.liuchat.api.event;

import com.liu.liuchat.api.Mail;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** 邮件成功落库后触发（不能取消）。 */
public final class MailReceivedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Mail mail;

    public MailReceivedEvent(Mail mail) {
        this.mail = mail;
    }

    public Mail getMail() { return mail; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}

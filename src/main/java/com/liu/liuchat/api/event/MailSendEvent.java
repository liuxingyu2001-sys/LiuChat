package com.liu.liuchat.api.event;

import com.liu.liuchat.api.MailDraft;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** 邮件写入前触发；取消后不会发送。 */
public final class MailSendEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final MailDraft draft;
    private boolean cancelled;

    public MailSendEvent(MailDraft draft) {
        this.draft = draft;
    }

    public MailDraft getDraft() { return draft; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}

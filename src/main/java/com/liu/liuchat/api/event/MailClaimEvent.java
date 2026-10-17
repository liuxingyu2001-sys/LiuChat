package com.liu.liuchat.api.event;

import com.liu.liuchat.api.Mail;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** 领取附件前触发；取消后物品不会进入背包，邮件保持未领取。 */
public final class MailClaimEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final Mail mail;
    private boolean cancelled;

    public MailClaimEvent(Player player, Mail mail) {
        this.player = player;
        this.mail = mail;
    }

    public Player getPlayer() { return player; }
    public Mail getMail() { return mail; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}

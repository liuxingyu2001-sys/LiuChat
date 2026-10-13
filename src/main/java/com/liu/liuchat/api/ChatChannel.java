package com.liu.liuchat.api;

import java.util.Locale;
import java.util.Objects;

/** Immutable channel definition. Empty permissions allow everyone. GLOBAL uses legacy chat presentation/transport. */
public record ChatChannel(String id, String displayName, Scope scope, String sendPermission,
                          String receivePermission, String format, double radius) {
    public enum Scope { GLOBAL, SERVER, WORLD, NEARBY, MEMBERS }
    public ChatChannel {
        Objects.requireNonNull(id); Objects.requireNonNull(displayName); Objects.requireNonNull(scope);
        id = id.toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z][a-z0-9_-]{0,47}")) throw new IllegalArgumentException("Invalid channel id: " + id);
        sendPermission = Objects.requireNonNull(sendPermission);
        receivePermission = Objects.requireNonNull(receivePermission);
        format = Objects.requireNonNull(format);
        if (scope == Scope.GLOBAL && !receivePermission.isBlank())
            throw new IllegalArgumentException("GLOBAL is public; use SERVER or MEMBERS for restricted recipients");
        if (!Double.isFinite(radius) || radius <= 0) throw new IllegalArgumentException("Radius must be finite and positive");
    }
}

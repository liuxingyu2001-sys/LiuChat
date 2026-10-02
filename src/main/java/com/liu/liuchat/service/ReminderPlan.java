package com.liu.liuchat.service;

import org.bukkit.configuration.ConfigurationSection;

import java.time.*;
import java.util.*;

/** Validated schedules with stable wall-clock slots, independent of server uptime. */
public record ReminderPlan(String id, long intervalSeconds, List<LocalTime> times, ZoneId zone,
                           Set<DayOfWeek> days, Set<String> servers, List<String> messages,
                           String permission, boolean console) {
    public static List<ReminderPlan> parse(ConfigurationSection config) {
        ZoneId zone = ZoneId.of(config.getString("timezone", "Asia/Shanghai"));
        if (!config.getBoolean("enable", false)) return List.of();
        ConfigurationSection entries = config.getConfigurationSection("reminders");
        if (entries == null) return List.of();
        List<ReminderPlan> plans = new ArrayList<>();
        for (String id : entries.getKeys(false)) {
            ConfigurationSection entry = entries.getConfigurationSection(id);
            if (entry == null) throw new IllegalArgumentException("Invalid reminder: " + id);
            if (!entry.getBoolean("enable", true)) continue;
            long interval = entry.getLong("interval-seconds", 0);
            List<LocalTime> times = entry.getStringList("times").stream().map(LocalTime::parse).distinct().sorted().toList();
            if ((interval > 0) == !times.isEmpty() || interval < 0)
                throw new IllegalArgumentException(id + ": choose interval-seconds OR times");
            if (interval > 31_536_000) throw new IllegalArgumentException(id + ": interval exceeds one year");
            Set<DayOfWeek> days = new HashSet<>();
            for (Object day : entry.getList("days", List.of())) {
                if (!(day instanceof Number number) || number.doubleValue() != number.intValue()
                        || number.intValue() < 1 || number.intValue() > 7)
                    throw new IllegalArgumentException(id + ": days must contain integers 1 through 7");
                days.add(DayOfWeek.of(number.intValue()));
            }
            List<String> messages = entry.getStringList("messages");
            if (messages.isEmpty() || messages.stream().anyMatch(String::isBlank))
                throw new IllegalArgumentException(id + ": messages cannot be empty");
            plans.add(new ReminderPlan(id, interval, times, zone, Set.copyOf(days),
                    Set.copyOf(entry.getStringList("servers")), List.copyOf(messages),
                    entry.getString("permission", ""), entry.getBoolean("console", true)));
        }
        return List.copyOf(plans);
    }

    /** Returns the latest occurrence crossed; never floods players after a delayed tick. */
    public Optional<Instant> occurrence(Instant after, Instant now, String server) {
        if (!now.isAfter(after) || (!servers.isEmpty() && !servers.contains(server))) return Optional.empty();
        Instant candidate = null;
        if (intervalSeconds > 0) {
            long slot = Math.floorDiv(now.getEpochSecond(), intervalSeconds) * intervalSeconds;
            Instant time = Instant.ofEpochSecond(slot);
            if (days.isEmpty() || days.contains(time.atZone(zone).getDayOfWeek())) candidate = time;
        } else {
            LocalDate date = now.atZone(zone).toLocalDate();
            // Only inspect today and yesterday: missed reminders are not replayed as a backlog.
            for (int offset = 1; offset >= 0; offset--) {
                LocalDate day = date.minusDays(offset);
                if (!days.isEmpty() && !days.contains(day.getDayOfWeek())) continue;
                for (LocalTime time : times) {
                    LocalDateTime local = day.atTime(time);
                    var offsets = zone.getRules().getValidOffsets(local);
                    if (offsets.isEmpty()) continue; // Skip nonexistent times at daylight-saving transitions.
                    Instant instant = local.toInstant(offsets.getFirst()); // Repeated local times run once.
                    if (!instant.isAfter(now) && (candidate == null || instant.isAfter(candidate))) candidate = instant;
                }
            }
        }
        return candidate != null && candidate.isAfter(after) ? Optional.of(candidate) : Optional.empty();
    }
}

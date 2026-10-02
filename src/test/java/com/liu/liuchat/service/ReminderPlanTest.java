package com.liu.liuchat.service;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReminderPlanTest {
    private YamlConfiguration config() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("enable", true);
        config.set("timezone", "Asia/Shanghai");
        config.set("reminders.test.messages", List.of("&aHello"));
        return config;
    }

    @Test void intervalsAlignAcrossServersAndDoNotReplay() {
        var config = config();
        config.set("reminders.test.interval-seconds", 600);
        var plan = ReminderPlan.parse(config).getFirst();
        Instant before = Instant.parse("2026-10-05T11:59:59Z");
        Instant due = Instant.parse("2026-10-05T12:00:00Z");
        assertEquals(due, plan.occurrence(before, due, "lobby").orElseThrow());
        assertEquals(due, plan.occurrence(before, due.plusSeconds(1), "survival").orElseThrow());
        assertTrue(plan.occurrence(due, due.plusSeconds(1), "lobby").isEmpty());
        assertTrue(plan.occurrence(due, before, "lobby").isEmpty());
        assertEquals(due.plusSeconds(3600), plan.occurrence(before, due.plusSeconds(3600), "lobby").orElseThrow());
    }

    @Test void exactTimesUseTimezoneNumericWeekdaysAndServerFilter() {
        var config = config();
        config.set("reminders.test.times", List.of("20:00", "22:30:00"));
        config.set("reminders.test.days", List.of(1, 7));
        config.set("reminders.test.servers", List.of("lobby"));
        var plan = ReminderPlan.parse(config).getFirst();
        Instant monday = Instant.parse("2026-10-05T12:00:00Z");
        assertEquals(monday, plan.occurrence(monday.minusSeconds(1), monday, "lobby").orElseThrow());
        assertTrue(plan.occurrence(monday.minusSeconds(1), monday, "survival").isEmpty());
        Instant tuesday = monday.plusSeconds(86400);
        assertTrue(plan.occurrence(tuesday.minusSeconds(1), tuesday, "lobby").isEmpty());
        Instant sunday = Instant.parse("2026-10-11T12:00:00Z");
        assertEquals(sunday, plan.occurrence(sunday.minusSeconds(1), sunday, "lobby").orElseThrow());
    }

    @Test void supportsAllSevenNumericWeekdays() {
        var config = config();
        config.set("reminders.test.times", List.of("20:00"));
        for (int day = 1; day <= 7; day++) {
            config.set("reminders.test.days", List.of(day));
            var plan = ReminderPlan.parse(config).getFirst();
            Instant time = Instant.parse("2026-10-05T12:00:00Z").plusSeconds((day - 1) * 86400L);
            assertTrue(plan.occurrence(time.minusSeconds(1), time, "lobby").isPresent());
        }
    }

    @Test void rejectsInvalidWeekdaysAndConflictingSchedules() {
        var config = config();
        config.set("reminders.test.interval-seconds", 600);
        for (Object invalid : List.of(0, 8, 1.5, "MONDAY", "1")) {
            config.set("reminders.test.days", List.of(invalid));
            assertThrows(IllegalArgumentException.class, () -> ReminderPlan.parse(config));
        }
        config.set("reminders.test.days", List.of());
        config.set("reminders.test.times", List.of("20:00"));
        assertThrows(IllegalArgumentException.class, () -> ReminderPlan.parse(config));
        config.set("reminders.test.interval-seconds", 0);
        config.set("reminders.test.times", List.of("25:00"));
        assertThrows(java.time.DateTimeException.class, () -> ReminderPlan.parse(config));
    }

    @Test void daylightSavingTimesSkipGapsAndRunRepeatedHourOnlyOnce() {
        var config = config();
        config.set("timezone", "America/New_York");
        config.set("reminders.test.times", List.of("02:30"));
        var gap = ReminderPlan.parse(config).getFirst();
        assertTrue(gap.occurrence(Instant.parse("2026-03-08T06:59:59Z"),
                Instant.parse("2026-03-08T07:30:00Z"), "lobby").isEmpty());
        config.set("reminders.test.times", List.of("01:30"));
        var overlap = ReminderPlan.parse(config).getFirst();
        assertTrue(overlap.occurrence(Instant.parse("2026-11-01T05:29:59Z"),
                Instant.parse("2026-11-01T05:30:00Z"), "lobby").isPresent());
        assertTrue(overlap.occurrence(Instant.parse("2026-11-01T06:29:59Z"),
                Instant.parse("2026-11-01T06:30:00Z"), "lobby").isEmpty());
    }

    @Test void bundledRemindersAreDisabledByDefault() throws Exception {
        var config = new YamlConfiguration();
        try (var input = getClass().getResourceAsStream("/reminders.yml")) {
            config.load(new java.io.InputStreamReader(java.util.Objects.requireNonNull(input),
                    java.nio.charset.StandardCharsets.UTF_8));
        }
        assertTrue(ReminderPlan.parse(config).isEmpty());
        config.set("enable", true);
        assertEquals(1, ReminderPlan.parse(config).size());
    }
}

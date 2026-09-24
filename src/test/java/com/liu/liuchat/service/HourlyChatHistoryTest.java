package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HourlyChatHistoryTest {
    @TempDir Path folder;

    @Test void readsOnlySelectedHoursAcrossDays() throws Exception {
        Instant now = Instant.parse("2026-09-25T00:15:00Z");
        var zone = ZoneId.systemDefault();
        for (int hours : new int[]{1, 3, 8}) {
            Instant time = now.minusSeconds(hours * 3600L);
            Path file = folder.resolve(LocalDate.ofInstant(time, zone) + ".jsonl");
            Files.writeString(file, "{\"uuid\":\"u" + hours + "\",\"player\":\"Alice\",\"time\":\""
                    + time + "\",\"message\":\"hello\"}\n", java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        }
        var one = ChatLogService.readHistory(folder, 2 * 60, now);
        assertEquals(1, one.entries().size());
        assertEquals("u1", one.entries().getFirst().uuid());
        assertEquals(3, ChatLogService.readHistory(folder, 24 * 60, now).entries().size());
    }

    @Test void boundsHistoryToMostRecentMessages() {
        HourlyChatHistory history = new HourlyChatHistory();
        for (int i = 0; i < 260; i++) history.add("uuid-" + i, "P", "text", Instant.now());
        var snapshot = history.drain();
        assertEquals(250, snapshot.entries().size());
        assertEquals(10, snapshot.dropped());
        assertEquals("uuid-10", snapshot.entries().getFirst().uuid());
    }

    @Test void acceptsJsonWrappedInExplanationOrCodeFence() {
        HourlyChatHistory history = new HourlyChatHistory();
        history.add("uuid-1", "Alice", "test content", Instant.now());
        var findings = HourlyChatHistory.findings("结果如下：\n```json\n[{\"uuid\":\"uuid-1\",\"reason\":\"spam\",\"evidence\":\"test\"}]\n```", history.drain());
        assertEquals(1, findings.size());
    }

    @Test void rejectsNonJsonWithoutCreatingFindings() {
        HourlyChatHistory history = new HourlyChatHistory();
        history.add("uuid-1", "Alice", "test", Instant.now());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> HourlyChatHistory.findings("抱歉，我无法判断。", history.drain()));
    }

    @Test void acceptsOnlyKnownUuidAndQuotedEvidence() {
        HourlyChatHistory history = new HourlyChatHistory();
        history.add("uuid-1", "Alice", "test content", Instant.now());
        var snapshot = history.drain();
        String json = "[{\"uuid\":\"uuid-1\",\"reason\":\"fake\",\"evidence\":\"not said\"},"
                + "{\"uuid\":\"uuid-1\",\"reason\":\"spam\",\"evidence\":\"test\"},"
                + "{\"uuid\":\"other\",\"reason\":\"spam\",\"evidence\":\"test\"}]";
        var findings = HourlyChatHistory.findings(json, snapshot);
        assertEquals(1, findings.size());
        assertEquals("Alice", findings.getFirst().player());
        assertEquals("test", findings.getFirst().evidence());
        assertTrue(HourlyChatHistory.findings("[]", snapshot).isEmpty());
    }
}

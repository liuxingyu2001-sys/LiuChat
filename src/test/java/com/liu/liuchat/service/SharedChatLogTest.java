package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SharedChatLogTest {
    @TempDir Path directory;

    @Test void keepsLocalLayoutUnlessSharingIsRequested() {
        Path local = directory.resolve("local");
        assertEquals(local, ChatLogService.recordRoot(local, "", "lobby"));
        assertEquals(local, ChatLogService.recordRoot(local, " ", "lobby"));
    }

    @Test void isolatesServerIdsWithoutPathTraversalOrCollisions() {
        Path shared = directory.resolve("shared");
        Path local = directory.resolve("local");
        Path first = ChatLogService.recordRoot(local, shared.toString(), "lobby");
        Path second = ChatLogService.recordRoot(local, shared.toString(), "survival");
        assertNotEquals(first, second);
        assertEquals(shared.resolve("servers/server-6c6f626279"), first);
        assertTrue(ChatLogService.recordRoot(local, shared.toString(), "../../外部").startsWith(shared));
        assertNotEquals(ChatLogService.recordRoot(local, shared.toString(), "a/b"),
                ChatLogService.recordRoot(local, shared.toString(), "a_b"));
    }
}

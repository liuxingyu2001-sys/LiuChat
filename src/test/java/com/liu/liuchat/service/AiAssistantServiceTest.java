package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiAssistantServiceTest {
    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test void sharedMemoryUsesAssistantNameAsSessionKey() {
        assertEquals("bot", AiAssistantService.sessionKey("bot", PLAYER, true));
    }

    @Test void privateMemoryScopesSessionToPlayer() {
        assertEquals("bot#" + PLAYER, AiAssistantService.sessionKey("bot", PLAYER, false));
    }
}

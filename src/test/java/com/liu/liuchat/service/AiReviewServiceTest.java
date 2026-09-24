package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiReviewServiceTest {
    @Test void onlyExplicitDecisionsOverrideFailurePolicy() {
        assertTrue(AiReviewService.decision(" ALLOW ", null, false));
        assertFalse(AiReviewService.decision("block", null, true));
        assertFalse(AiReviewService.decision("ALLOW but...", null, false));
        assertTrue(AiReviewService.decision("maybe", null, true));
        assertFalse(AiReviewService.decision(null, new RuntimeException("timeout"), false));
    }
}

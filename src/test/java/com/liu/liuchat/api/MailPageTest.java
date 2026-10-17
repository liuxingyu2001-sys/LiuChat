package com.liu.liuchat.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailPageTest {

    @Test
    void hasNextUsesTotalNotCurrentPageSize() {
        assertTrue(new MailPage(List.of(), 1, 10, 21, 0).hasNext());
        assertFalse(new MailPage(List.of(), 2, 10, 20, 0).hasNext());
        assertTrue(new MailPage(List.of(), 9, 10, 91, 0).hasNext());
        assertFalse(new MailPage(List.of(), 10, 10, 91, 0).hasNext());
    }
}

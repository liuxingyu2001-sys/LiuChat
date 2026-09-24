package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatLogCleanTest {
    @Test void stripsLegacyAndHexColorCodes() {
        assertEquals("弄错了 擦", ChatLogService.clean("§x§0§0§D§F§E§0弄§x§0§0§D§7§D§4错§x§0§0§C§F§C§8了§x§0§0§C§6§B§B §x§0§0§B§E§A§F擦"));
        assertEquals("hello", ChatLogService.clean("§chello§r"));
        assertEquals("bold text", ChatLogService.clean("§lbold§r text"));
    }

    @Test void keepsLiteralAmpersandTextAndCollapsesNewlines() {
        assertEquals("R&D &c raw", ChatLogService.clean("R&D &c raw"));
        assertEquals("a b", ChatLogService.clean("a\r\nb"));
        assertEquals("", ChatLogService.clean(null));
    }

    @Test void keepsPlainFieldsUntouched() {
        assertEquals("[01:13:05] [CHAT] [99] Rosesunfading -> *: ok了",
                ChatLogService.clean("[01:13:05] [CHAT] [99] Rosesunfading -> *: ok了"));
    }
}

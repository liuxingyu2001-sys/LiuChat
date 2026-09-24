package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAnswerFormatterTest {
    @Test void removesMarkdownKeepsCommandsAndWrapsLongText() {
        var lines = AiAnswerFormatter.lines("**先打开菜单**，然后执行 `/spawn`。\n" + "说明".repeat(30));
        assertTrue(lines.stream().anyMatch(line -> line.contains("先打开菜单")));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("&b/spawn")));
        assertFalse(lines.stream().anyMatch(line -> line.contains("**") || line.contains("`")));
        assertTrue(lines.size() > 3);
    }

    @Test void emptyAnswerGetsFallback() {
        assertTrue(AiAnswerFormatter.lines("").getFirst().contains("没有返回内容"));
    }
}

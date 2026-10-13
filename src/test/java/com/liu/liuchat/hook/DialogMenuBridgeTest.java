package com.liu.liuchat.hook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogMenuBridgeTest {

    private static Logger silentLogger() {
        Logger logger = Logger.getLogger("DialogMenuBridgeTest." + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }

    private static ByteArrayInputStream yaml(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void writesMenuOnlyWhenMissing(@TempDir File dir) throws IOException {
        File target = new File(dir, "menus/liuchat.yml");
        assertTrue(DialogMenuBridge.writeIfMissing(target, yaml("Version: 1\n"), silentLogger()));
        assertTrue(target.isFile());

        // 已存在的菜单是用户改过的，绝不能被覆盖
        Files.writeString(target.toPath(), "用户改过的内容");
        assertFalse(DialogMenuBridge.writeIfMissing(target, yaml("Version: 1\n"), silentLogger()));
        assertEquals("用户改过的内容", Files.readString(target.toPath()));
    }

    @Test
    void readsPageIdsInDeclaredOrder() {
        String menu = """
                Version: 1
                Type: settings
                Pages:
                  help:
                    Layout: []
                  chat:
                    Layout: []
                """;
        assertEquals(List.of("help", "chat"), DialogMenuBridge.pages(yaml(menu)));
    }

    @Test
    void missingOrBrokenPagesYieldEmptyList() {
        assertEquals(List.of(), DialogMenuBridge.pages(yaml("Pages:\n")));
        assertEquals(List.of(), DialogMenuBridge.pages(yaml(":::not yaml:::\n")));
    }
}

package com.liu.liuchat.service;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CraftEngineNamesTest {
    @TempDir Path dir;

    @Test void resolvesCraftEngineComponentAndLegacyNames() {
        CraftEngineNames names = new CraftEngineNames(Map.of("item.default.topaz_sword", "黄玉剑"));
        assertEquals("黄玉剑", names.resolve(Component.translatable("item.default.topaz_sword")));
        assertEquals("黄玉剑!", names.resolve(Component.translatable("item.default.topaz_sword")
                .append(Component.text("!"))));
        assertEquals("传说黄玉剑", names.resolve(Component.text("传说")
                .append(Component.translatable("item.default.topaz_sword"))));
        assertEquals("<!i><#FF8C00>黄玉剑", names.resolve("<!i><#FF8C00><lang:item.default.topaz_sword>"));
        assertEquals("黄玉剑", names.resolve("item.default.topaz_sword"));
        assertEquals("黄玉剑", names.resolve("item.custom:topaz_sword"));
    }

    @Test void parsesCraftEngineLangSection() throws Exception {
        Path file = dir.resolve("zh_cn.yml");
        Files.writeString(file, "lang#items:\n  zh_cn:\n    item.default.sword: 附魔剑\n"
                + "lang#custom:\n  zh_cn:\n    item.other.sword: 自定义剑\n");
        assertEquals("附魔剑", CraftEngineNames.readFile(file.toFile()).get("item.default.sword"));
        assertEquals("自定义剑", CraftEngineNames.readFile(file.toFile()).get("item.other.sword"));
    }
}

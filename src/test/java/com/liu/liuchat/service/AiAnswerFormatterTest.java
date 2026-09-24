package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAnswerFormatterTest {
    @Test void removesMarkdownKeepsCommandsAndWrapsLongText() {
        var lines = AiAnswerFormatter.lines("**先打开菜单**，然后执行 `/spawn`。\n" + "说明".repeat(30));
        assertTrue(lines.stream().anyMatch(line -> line.contains("先打开菜单")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("&b/spawn")), lines.toString());
        // 指令段末尾复位颜色，整段回答拼成一条消息后不会一路染色
        assertTrue(lines.stream().anyMatch(line -> line.contains("&b/spawn&f。")), lines.toString());
        assertFalse(lines.stream().anyMatch(line -> line.contains("**") || line.contains("`")));
        assertTrue(lines.size() > 3);
    }

    @Test void emptyAnswerGetsFallback() {
        assertTrue(AiAnswerFormatter.lines("").getFirst().contains("没有返回内容"));
    }

    @Test void keepsAngleBracketAndUnpairedMarkdownInCommands() {
        // 指令占位符 <名称> 的 >、坐标 ~、下划线都是正文，不能被当 Markdown 吃掉
        var lines = AiAnswerFormatter.lines("2. 输入 `/res create <名称>` 创建领地\n用 `/tp ~ ~ ~` 回家，文件写 player_name");
        assertTrue(lines.stream().anyMatch(line -> line.contains("<名称>")), lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.contains("/tp ~ ~ ~")), lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.contains("player_name")), lines.toString());
    }

    @Test void stripsOnlyLineStartHeadingAndPairedEmphasis() {
        var lines = AiAnswerFormatter.lines("## 领地\n正文 #RRGGBB 颜色 **成对** 与 单个*星");
        assertTrue(lines.contains("领地"), lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.contains("#RRGGBB") && line.contains("单个*星")
                && !line.contains("**")), lines.toString());
    }

    @Test void blockKeepsParagraphsInOneMultiLineMessage() {
        // 多段回答用换行分段，聊天框只发一条消息
        String block = AiAnswerFormatter.block("第一段\n\n第二段\n\n\n第三段", 0);
        assertTrue(block.contains("第一段\n\n第二段\n\n第三段"), block);
        assertFalse(block.startsWith("\n"));
    }

    @Test void blockReservesRoomForAnswerPrefix() {
        String line = AiAnswerFormatter.block("说明".repeat(30), AiAnswerFormatter.visibleWidth("&b[久久酱] ")).lines().findFirst().orElseThrow();
        // 首行让出前缀宽度后，正文（全角算 2）不会顶出聊天框
        assertTrue(AiAnswerFormatter.visibleWidth(line) <= 46 - AiAnswerFormatter.visibleWidth("&b[久久酱] "), line);
    }

    @Test void visibleWidthIgnoresColorCodesAndCountsFullWidthAsTwo() {
        assertEquals(0, AiAnswerFormatter.visibleWidth(""));
        assertEquals(0, AiAnswerFormatter.visibleWidth("&b"));
        assertEquals(4, AiAnswerFormatter.visibleWidth("久久"));
        assertEquals(3, AiAnswerFormatter.visibleWidth("§cabc"));
        assertEquals(9, AiAnswerFormatter.visibleWidth("&b[久久酱] "));
    }

    @Test void wrapsLongChineseLineWithoutLeavingWholeSentenceOnOneLine() {
        var lines = AiAnswerFormatter.lines("说明".repeat(30));
        assertTrue(lines.size() >= 3, lines.toString());
        assertTrue(lines.stream().allMatch(line -> AiAnswerFormatter.visibleWidth(line) <= 46), lines.toString());
    }
}

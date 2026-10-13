package com.liu.liuchat.hook;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置 DialogMenu 菜单的契约测试：结构合法、Layout/Icons 对得上，
 * 并且菜单里用的 %liuchat_...% 必须是 LiuChatExpansion 真正实现了的变量。
 * <p>
 * 这份菜单是写给另一个插件解析的，两边只有这一层约定，改名不改测试就会在游戏里显示“未接入”。
 */
class BundledMenuTest {

    private static final Set<String> SUPPORTED = Set.of(
            "nick", "nick_raw", "cooldown", "ignore_count", "horn_balance",
            "recent_contact", "ai", "muted", "muted_time", "muted_reason",
            "server", "world");

    private static InputStream menu() {
        InputStream in = BundledMenuTest.class.getClassLoader()
                .getResourceAsStream("dialogmenu/liuchat.yml");
        if (in != null) return in;
        // 回退：测试类不在 target/classes 场景时直接读源码资源
        try {
            return Files.newInputStream(new File("src/main/resources/dialogmenu/liuchat.yml").toPath());
        } catch (Exception e) {
            return null;
        }
    }

    @Test
    void menuStructureMatchesDialogMenuContract() throws Exception {
        try (InputStream source = menu()) {
            assertNotNull(source, "内置菜单资源缺失");
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new java.io.InputStreamReader(source, java.nio.charset.StandardCharsets.UTF_8));

            assertEquals(1, yaml.getInt("Version"));
            assertEquals("settings", yaml.getString("Type"));
            ConfigurationSection pages = yaml.getConfigurationSection("Pages");
            assertNotNull(pages, "Pages 段缺失");
            assertTrue(pages.getKeys(false).contains(yaml.getString("DefaultPage")),
                    "DefaultPage 必须指向存在的页");

            for (String pageId : pages.getKeys(false)) {
                ConfigurationSection page = pages.getConfigurationSection(pageId);
                assertNotNull(page, pageId + " 不是配置段");
                ConfigurationSection icons = page.getConfigurationSection("Icons");
                assertNotNull(icons, pageId + " 缺少 Icons");
                List<String> layout = page.getStringList("Layout");
                assertTrue(layout.size() > 0, pageId + " 的 Layout 为空");
                for (String item : layout) {
                    assertTrue(icons.isConfigurationSection(item),
                            pageId + " 的 Layout 引用了未定义控件: " + item);
                }
                for (String item : icons.getKeys(false)) {
                    ConfigurationSection control = icons.getConfigurationSection(item);
                    String type = control.getString("Type", "button");
                    if ("text".equals(type) || "heading".equals(type)) {
                        assertFalse(control.contains("Actions") || control.contains("Bind")
                                        || control.contains("State"),
                                pageId + "." + item + " 是静态控件，不能带 Actions/Bind/State");
                    } else if ("button".equals(type)) {
                        assertTrue(control.contains("Actions"),
                                pageId + "." + item + " 是按钮，必须有 Actions");
                    }
                    if (control.contains("Actions")) {
                        for (String action : control.getStringList("Actions")) {
                            assertTrue(action.startsWith("command: ") || action.equals("close")
                                            || action.equals("refresh"),
                                    "不支持的动作: " + action);
                        }
                    }
                }
            }
        }
    }

    @Test
    void menuOnlyUsesPlaceholdersThatExistInLiuChatExpansion() throws Exception {
        try (InputStream source = menu()) {
            assertNotNull(source, "内置菜单资源缺失");
            String text = new String(source.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("%liuchat_([a-z_]+)%").matcher(text);
            boolean found = false;
            while (matcher.find()) {
                found = true;
                assertTrue(SUPPORTED.contains(matcher.group(1)),
                        "菜单用了 LiuChatExpansion 没有的变量: %liuchat_" + matcher.group(1) + "%");
            }
            assertTrue(found, "菜单里应至少有一个 %liuchat_...% 状态变量");
        }
    }

    /**
     * 镜像 DialogMenu 0.1.21 的排版容量规则（SimpleMenuParser）：
     * 标题占 1 行、正文从第 3 行起，控件高度 = max(2, 1) + 说明行数；
     * 超过第 9 行自动切下方面板，下方面板从 13 行起、最深到 24 行；
     * 显式 heading 再次切面板、或控件超过 24 行都会被 /dmenu check 拒绝。
     * 这里只是提前拦住手写菜单溢出，真正权威的仍是游戏内的 /dmenu check。
     */
    @Test
    void menuPagesFitInTheTwoPanels() throws Exception {
        try (InputStream source = menu()) {
            assertNotNull(source, "内置菜单资源缺失");
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new java.io.InputStreamReader(source, java.nio.charset.StandardCharsets.UTF_8));
            ConfigurationSection pages = yaml.getConfigurationSection("Pages");
            assertNotNull(pages);
            for (String pageId : pages.getKeys(false)) {
                ConfigurationSection page = pages.getConfigurationSection(pageId);
                ConfigurationSection icons = page.getConfigurationSection("Icons");
                int row = 3; // 标题 heading 在第 1 行，字号 8 占 1 行
                boolean lower = false;
                for (String item : page.getStringList("Layout")) {
                    ConfigurationSection control = icons.getConfigurationSection(item);
                    String type = control.getString("Type", "button");
                    if ("heading".equals(type)) {
                        assertFalse(lower, pageId + "." + item + " 重复切换下方面板，/dmenu check 会拒绝");
                        lower = true;
                        row = 13;
                        continue;
                    }
                    Object description = control.get("Description");
                    int descriptions = description == null ? 0
                            : description instanceof java.util.List<?> list ? list.size() : 1;
                    int height = 2 + descriptions; // 字号 8 时每行 1 个 row
                    if (!lower && row + height > 9) {
                        lower = true;
                        row = 13;
                    }
                    assertTrue(row + height <= 24,
                            pageId + "." + item + " 超出下方面板（到第 " + (row + height) + " 行）");
                    row += height;
                }
            }
        }
    }
}

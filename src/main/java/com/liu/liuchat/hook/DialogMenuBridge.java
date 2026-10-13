package com.liu.liuchat.hook;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.logging.Level;

/**
 * DialogMenu 软依赖桥：内置菜单的安装与页 ID 解析。
 * <p>
 * 刻意<b>不引用 DialogMenu 的任何类</b>——只用 Bukkit 插件查询和文件操作，
 * 与 CraftEngine/Nameplates 的类隔离做法一致：未安装、类缺失都不会触发
 * {@code NoClassDefFoundError}。功能面是「命令 + PAPI 变量」：DialogMenu 没有
 * 对外 Java API，菜单按钮最终执行的仍是 LiuChat 自己的命令，权限由 LiuChat 校验。
 * <p>
 * 文件只在缺失时复制，用户改过的菜单不会被覆盖；菜单若被删掉，下次执行
 * {@code /liuc menu} 或 {@code /liuc reload} 会重新补上。
 */
public final class DialogMenuBridge {

    /** 内置菜单 ID，也是写入 DialogMenu 的文件名。 */
    public static final String MENU_ID = "liuchat";
    /** 内置菜单资源路径（子目录前缀避免被 ConfigDefaults 当成本插件配置复制）。 */
    private static final String RESOURCE = "dialogmenu/" + MENU_ID + ".yml";
    private static final String PLUGIN_NAME = "DialogMenu";

    private DialogMenuBridge() { }

    /** DialogMenu 是否已安装且启用（供命令可用性判断）。 */
    public static boolean available() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        return plugin != null && plugin.isEnabled();
    }

    /**
     * 把内置菜单写进 {@code plugins/DialogMenu/menus/liuchat.yml}。
     *
     * @return true = 本次写入了新文件；false = 未安装、缺失资源或文件已存在
     */
    public static boolean install(JavaPlugin owner) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        if (plugin == null) {
            return false;
        }
        try (InputStream source = owner.getResource(RESOURCE)) {
            if (source == null) {
                owner.getLogger().warning("内置 DialogMenu 菜单资源缺失: " + RESOURCE);
                return false;
            }
            return writeIfMissing(new File(plugin.getDataFolder(), "menus/" + MENU_ID + ".yml"),
                    source, owner.getLogger());
        } catch (IOException e) {
            owner.getLogger().log(Level.WARNING, "安装 DialogMenu 菜单失败", e);
            return false;
        }
    }

    /** 复制菜单文件，只在目标缺失时写入（已存在返回 false，不覆盖）。包级可见以便单测。 */
    static boolean writeIfMissing(File target, InputStream source, java.util.logging.Logger log) throws IOException {
        if (target.exists()) {
            return false;
        }
        File parent = target.getParentFile();
        if (parent != null) Files.createDirectories(parent.toPath());
        try {
            // 不带 COPY_ATTRIBUTES：来源是 jar 内资源，属性无意义，且部分文件系统不支持
            Files.copy(source, target.toPath());
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            return false; // 另一台子服或 DialogMenu 自己刚创建
        }
        log.info("已安装 DialogMenu 菜单: " + target.getPath() + "（执行 /dmenu reload 生效）");
        return true;
    }

    /** 内置菜单的页 ID（tab 补全用）；解析失败返回空列表，不影响命令执行。 */
    public static List<String> pages(JavaPlugin owner) {
        try (InputStream source = owner.getResource(RESOURCE)) {
            return source == null ? List.of() : pages(source);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /** 从菜单流里读 {@code Pages} 的键顺序；包级可见以便单测。 */
    static List<String> pages(InputStream source) {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(source, StandardCharsets.UTF_8));
            ConfigurationSection pages = yaml.getConfigurationSection("Pages");
            return pages == null ? List.of() : List.copyOf(pages.getKeys(false));
        } catch (Exception e) {
            return List.of();
        }
    }
}

package com.liu.liuchat.hook;

import com.fentai.arcmenu.api.ArcMenuApi;
import com.fentai.arcmenu.api.ArcMenuApplicationHandle;
import com.fentai.arcmenu.api.ArcMenuApplicationOptions;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * ArcMenu 版聊天工作区：注册 {@code liuchat:chat} 应用并提供打开入口。
 * <p>
 * 应用 ID 是命名空间形式，ArcMenu 的 YAML 菜单可以用
 * {@code actions: ['open-app: liuchat:chat']} 反向跳进本界面，
 * Java 侧则直接 {@link #open(Player, List)}。会话生命周期（实体清理、
 * 返回栈、玩家退出/插件停用）全部由 ArcMenu 托管。
 * <p>
 * 本类<b>只应在 {@link ArcMenuBridge#available()} 通过后加载</b>。
 */
final class ArcMenuWorkspace implements ChatWorkspace {

    /** 应用 ID；ArcMenu YAML 里 open-app 引用的就是它。 */
    static final String APP_ID = "liuchat:chat";

    private final ArcMenuApi api;
    private final ArcMenuApplicationHandle handle;

    ArcMenuWorkspace(JavaPlugin owner, ChatWorkspace.Deps deps) {
        ArcMenuApi loaded = Bukkit.getServicesManager().load(ArcMenuApi.class);
        if (loaded == null) {
            throw new IllegalStateException("ArcMenu API 服务未注册");
        }
        this.api = loaded;
        this.handle = loaded.registerApplication(owner, APP_ID,
                ArcMenuApplicationOptions.builder()
                        .permission("")                 // 权限由 LiuChat 自己的命令校验
                        .captureMouseScroll(true)       // 鼠标模式下滚轮翻动列表
                        .tickInterval(2)
                        .build(),
                context -> new ChatWorkspaceSession(context, deps));
    }

    @Override
    public boolean open(Player player, List<String> arguments) {
        try {
            return api.openApplication(player, APP_ID, arguments);
        } catch (RuntimeException ignored) {
            return false; // ArcMenu 会话已存在或已被关闭：交给调用方提示
        }
    }

    @Override
    public void close() {
        handle.unregister();
    }
}

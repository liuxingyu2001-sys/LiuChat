package com.liu.liuchat.hook;

import com.fentai.arcmenu.api.ArcMenuActivateEvent;
import com.fentai.arcmenu.api.ArcMenuApplicationCloseEvent;
import com.fentai.arcmenu.api.ArcMenuApplicationContext;
import com.fentai.arcmenu.api.ArcMenuApplicationSession;
import com.fentai.arcmenu.api.ArcMenuPointerMoveEvent;
import com.fentai.arcmenu.api.ArcMenuScrollEvent;
import com.fentai.arcmenu.api.ArcMenuSurface;
import com.fentai.arcmenu.api.ArcMenuSurfaceEvent;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天工作区会话：一行一个 TextDisplay，悬停/滚轮选中，右键触发。
 * <p>
 * 数据全部来自 {@link ChatWorkspace.Deps}（LiuChat 自己的服务），
 * 本类只负责排版、命中与把动作转成 LiuChat 已有命令。
 * 实体通过 {@code context.spawnPrivate} 生成——只对当前玩家可见，
 * 且在换屏、玩家退出、插件停用、应用替换、回调异常时由 ArcMenu 统一清理。
 */
final class ChatWorkspaceSession extends ArcMenuApplicationSession {

    private static final double TEXT_X = -120.0;   // 画布左侧
    private static final double DEPTH = 2.0;       // 正 depth 朝向玩家
    private static final float TEXT_SCALE = 0.75f;

    /** 三个页面循环切换。 */
    private enum Page {
        STATUS("状态"), IGNORED("屏蔽列表"), RECENT("最近联系人");

        private final String label;

        Page(String label) {
            this.label = label;
        }
    }

    private final ArcMenuApplicationContext context;
    private final ChatWorkspace.Deps deps;
    private final WorkspaceModel model = new WorkspaceModel();
    private final List<TextDisplay> displays = new ArrayList<>();
    private ArcMenuSurface surface;
    private Page page = Page.STATUS;

    ChatWorkspaceSession(ArcMenuApplicationContext context, ChatWorkspace.Deps deps) {
        this.context = context;
        this.deps = deps;
        this.surface = context.surface();
        render();
    }

    // ---------------- 生命周期 ----------------

    @Override
    public void onSurfaceChanged(ArcMenuSurfaceEvent event) {
        // 传送/重生/切世界会先移除本会话创建的实体，这里按新屏幕平面重建
        surface = event.getSurface();
        displays.clear();
        render();
    }

    @Override
    public void onClose(ArcMenuApplicationCloseEvent event) {
        displays.clear(); // ArcMenu 已移除实体，这里只放掉引用
    }

    // ---------------- 输入 ----------------

    @Override
    public void onPointerMove(ArcMenuPointerMoveEvent event) {
        var point = event.getPoint();
        int index = point == null ? -1 : model.hit(point.getY());
        if (index < 0) {
            context.setCursorInteractive(false);
            return;
        }
        context.setCursorInteractive(true);
        if (index != model.selected()) {
            model.select(index);
            repaint();
        }
    }

    @Override
    public void onActivate(ArcMenuActivateEvent event) {
        var point = event.getPoint();
        int index = point == null ? model.selected() : model.hit(point.getY());
        if (index >= 0) {
            model.activate(index);
        }
    }

    @Override
    public void onScroll(ArcMenuScrollEvent event) {
        model.move(event.getSteps() > 0 ? 1 : -1);
        repaint();
    }

    // ---------------- 排版 ----------------

    /** 页面切换等行数变化时重建；选中态变化只改文字。 */
    private void repaint() {
        if (model.rows().size() + 1 != displays.size()) {
            render();
            return;
        }
        for (int i = 0; i < model.rows().size(); i++) {
            displays.get(i + 1).setText(rowText(model.rows().get(i), i == model.selected()));
        }
    }

    private void render() {
        clearDisplays();
        model.setRows(buildRows());
        displays.add(label("§f§lLiuChat 聊天工作区", WorkspaceModel.yOf(-2), false));
        for (int i = 0; i < model.rows().size(); i++) {
            displays.add(label(rowText(model.rows().get(i), i == model.selected()),
                    WorkspaceModel.yOf(i), false));
        }
    }

    private String rowText(WorkspaceModel.Row row, boolean selected) {
        String prefix = selected ? "§e▶ " : "§8  ";
        return prefix + (selected ? "§f" : "§7") + row.text();
    }

    private TextDisplay label(String text, double y, boolean bold) {
        ArcMenuSurface plane = surface != null ? surface : context.surface();
        return context.spawnPrivate(plane.toWorld(TEXT_X, y, DEPTH), TextDisplay.class, display -> {
            display.setText(bold ? "§f§l" + text : text);
            display.setBillboard(Display.Billboard.CENTER);
            display.setShadowed(true);
            display.setSeeThrough(false);
            display.setViewRange(2.0f);
            display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(TEXT_SCALE, TEXT_SCALE, TEXT_SCALE), new Quaternionf()));
        });
    }

    private void clearDisplays() {
        for (TextDisplay display : displays) {
            if (display.isValid()) display.remove();
        }
        displays.clear();
    }

    // ---------------- 内容 ----------------

    private List<WorkspaceModel.Row> buildRows() {
        List<WorkspaceModel.Row> rows = new ArrayList<>();
        for (Page candidate : Page.values()) {
            Page target = candidate;
            rows.add(new WorkspaceModel.Row(
                    (target == page ? "§a" : "") + "「" + target.label + "」",
                    () -> switchPage(target)));
        }
        switch (page) {
            case STATUS -> statusRows(rows);
            case IGNORED -> ignoredRows(rows);
            case RECENT -> recentRows(rows);
        }
        rows.add(new WorkspaceModel.Row("关闭", context::close));
        return rows;
    }

    private void statusRows(List<WorkspaceModel.Row> rows) {
        Player player = context.getPlayer();
        String muted = deps.muteText(player);
        rows.add(new WorkspaceModel.Row("昵称：" + deps.nickname(player), null));
        rows.add(new WorkspaceModel.Row(muted.isEmpty() ? "未被禁言" : "禁言中：" + muted, null));
        rows.add(new WorkspaceModel.Row("发言冷却：" + deps.cooldown(player) + " 秒", null));
        int balance = deps.hornBalance(player);
        rows.add(new WorkspaceModel.Row("喇叭余额：" + (balance < 0 ? "存储不可用" : balance), null));
    }

    private void ignoredRows(List<WorkspaceModel.Row> rows) {
        Player player = context.getPlayer();
        List<String> names = deps.ignored(player);
        if (names.isEmpty()) {
            rows.add(new WorkspaceModel.Row("没有屏蔽任何人", null));
            return;
        }
        for (String name : names.stream().limit(WorkspaceModel.MAX_ROWS).toList()) {
            rows.add(new WorkspaceModel.Row("取消屏蔽 " + name, () -> unignore(player, name)));
        }
        if (names.size() > WorkspaceModel.MAX_ROWS) {
            rows.add(new WorkspaceModel.Row("共 " + names.size() + " 人，仅显示前 "
                    + WorkspaceModel.MAX_ROWS + " 人", null));
        }
    }

    private void recentRows(List<WorkspaceModel.Row> rows) {
        String contact = deps.recentContact(context.getPlayer());
        if (contact == null) {
            rows.add(new WorkspaceModel.Row("还没有成功收发的私聊", null));
            return;
        }
        rows.add(new WorkspaceModel.Row("最近私聊：" + contact, null));
        rows.add(new WorkspaceModel.Row("输入 /r <消息> 即可回复", null));
    }

    private void unignore(Player player, String name) {
        if (deps.unignore(player, name)) {
            render(); // 行数变了，重建
        } else {
            repaint();
        }
    }

    private void switchPage(Page target) {
        if (target == page) return;
        page = target;
        render();
    }
}

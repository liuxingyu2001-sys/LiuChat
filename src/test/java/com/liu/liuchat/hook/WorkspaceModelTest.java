package com.liu.liuchat.hook;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 工作区行列表的选中/命中逻辑（不依赖 Bukkit 与 ArcMenu）。 */
class WorkspaceModelTest {

    private static WorkspaceModel with(int rows) {
        WorkspaceModel model = new WorkspaceModel();
        java.util.List<WorkspaceModel.Row> list = new java.util.ArrayList<>();
        for (int i = 0; i < rows; i++) list.add(new WorkspaceModel.Row("行" + i, null));
        model.setRows(list);
        return model;
    }

    @Test
    void hitPicksNearestRowInsideHalfStep() {
        WorkspaceModel model = with(3);
        assertEquals(0, model.hit(WorkspaceModel.yOf(0)));
        assertEquals(1, model.hit(WorkspaceModel.yOf(1) + 5)); // 半行距内算同一行
        assertEquals(-1, model.hit(WorkspaceModel.yOf(0) + WorkspaceModel.STEP * 3)); // 第一行上方
        assertEquals(-1, model.hit(WorkspaceModel.yOf(2) - WorkspaceModel.STEP * 3));
        assertEquals(-1, with(0).hit(WorkspaceModel.yOf(0)));
    }

    @Test
    void selectionClampsAtBothEnds() {
        WorkspaceModel model = with(3);
        model.move(1);
        assertEquals(1, model.selected());
        model.select(99);
        assertEquals(2, model.selected());
        model.select(-5);
        assertEquals(0, model.selected());
        model.move(-1);
        assertEquals(0, model.selected());
    }

    @Test
    void shrinkingRowsKeepsSelectionInRange() {
        WorkspaceModel model = with(4);
        model.select(3);
        model.setRows(WorkspaceModel.infos("只剩一行"));
        assertEquals(0, model.selected());
    }

    @Test
    void activateRunsOnlyRowsWithAction() {
        AtomicInteger runs = new AtomicInteger();
        WorkspaceModel model = new WorkspaceModel();
        model.setRows(List.of(
                new WorkspaceModel.Row("说明", null),
                new WorkspaceModel.Row("按钮", runs::incrementAndGet)));
        model.activate(0);
        model.activate(5); // 越界
        assertEquals(0, runs.get());
        model.activate(1);
        assertEquals(1, runs.get());
        assertFalse(model.rows().isEmpty());
        assertTrue(model.rows().get(0).action() == null);
    }

    @Test
    void pageFitsEightBusinessRowsPlusChrome() {
        // 导航 3 行 + 业务行 + 关闭 1 行；业务行被截到 MAX_ROWS
        WorkspaceModel model = with(3 + WorkspaceModel.MAX_ROWS + 1);
        for (int i = 0; i < model.rows().size(); i++) {
            assertTrue(WorkspaceModel.yOf(i) > -90.0, "第 " + i + " 行超出画布下边界");
        }
    }
}

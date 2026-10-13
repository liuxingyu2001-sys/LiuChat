package com.liu.liuchat.hook;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作区的行列表与选中/命中计算：纯逻辑，不碰 Bukkit 与界面插件，方便单测。
 * <p>
 * 行按固定行距自上而下排布（逻辑画布坐标，中心为 (0,0)，Y 向上），
 * 指针命中取最近的一行，且必须落在该行 ±半行距内，否则视为没有悬停。
 */
final class WorkspaceModel {

    /** 单页最多显示的业务行数，超出折叠成提示行。 */
    static final int MAX_ROWS = 8;
    /** 第一行的 Y（画布高 180，中心 0）。 */
    static final double TOP_Y = 54.0;
    /** 行距。 */
    static final double STEP = 12.0;

    /** action 为 null 的行只显示不可触发（如说明文字）。 */
    record Row(String text, Runnable action) { }

    private List<Row> rows = List.of();
    private int selected;

    void setRows(List<Row> next) {
        rows = List.copyOf(next);
        selected = clamp(selected);
    }

    List<Row> rows() {
        return rows;
    }

    int selected() {
        return selected;
    }

    void select(int index) {
        selected = clamp(index);
    }

    /** 正数向下、负数向上移动选中项。 */
    void move(int delta) {
        select(selected + delta);
    }

    /** 执行指定行的动作（越界或无动作则什么都不做）。 */
    void activate(int index) {
        if (index < 0 || index >= rows.size()) return;
        Runnable action = rows.get(index).action();
        if (action != null) action.run();
    }

    /** 指定行的画布 Y 坐标。 */
    static double yOf(int index) {
        return TOP_Y - index * STEP;
    }

    /** 指针 Y 命中的行号；没有命中返回 -1。 */
    int hit(double y) {
        if (rows.isEmpty()) return -1;
        int index = (int) Math.round((TOP_Y - y) / STEP);
        if (index < 0 || index >= rows.size()) return -1;
        return Math.abs(yOf(index) - y) <= STEP / 2.0 + 1.0 ? index : -1;
    }

    private int clamp(int index) {
        return rows.isEmpty() ? 0 : Math.max(0, Math.min(index, rows.size() - 1));
    }

    /** 便于调用方拼装：把普通文本行转成不可触发的说明行。 */
    static List<Row> infos(String... texts) {
        List<Row> rows = new ArrayList<>(texts.length);
        for (String text : texts) rows.add(new Row(text, null));
        return rows;
    }
}

package com.liu.liuchat.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 重复/相似发言检测：时间窗口内与上次发言完全相同或相似度达标则判为刷屏。
 *
 * <ul>
 *   <li>完全相同不受 {@code min-length} 限制；相似度比较要求当前消息长度达标；</li>
 *   <li>「[i] 物品展示」消息整条跳过：每次展示的物品都可能不同，同文案不算刷屏，
 *       且不参与「上次发言」记录（否则可以靠 [i] 交替发言绕过检测）；</li>
 *   <li>被拦下的消息不刷新记录，窗口内连续刷屏会一直拦。</li>
 * </ul>
 *
 * <p>并发安全：聊天处理运行在异步聊天线程。
 */
public final class RepeatCheck {

    private record Said(long time, String text) {
    }

    private final Map<UUID, Said> lastSaid = new ConcurrentHashMap<>();

    /**
     * 判断这条消息是否算刷屏；不刷屏时记为该玩家的「上次发言」。
     *
     * @param windowSeconds 检测时间窗口（秒），{@code <= 0} 关闭；上次发言超过窗口则不比较
     * @param similarity    相似度百分比，100 = 必须完全一致才拦截
     * @param minLength     参与相似度比较的最短消息长度，短于该长度只拦完全相同的
     * @param itemShow      是否是 [i] 物品展示消息（跳过检测，也不记录）
     * @return {@code true} = 刷屏，应拦截该条消息
     */
    public boolean spam(UUID id, String message, long now,
                        int windowSeconds, double similarity, int minLength, boolean itemShow) {
        if (itemShow || windowSeconds <= 0 || id == null || message == null) {
            return false;
        }
        String current = message.trim();
        if (current.isEmpty()) {
            return false;
        }
        Said prev = lastSaid.get(id);
        boolean spam;
        if (prev == null || now - prev.time() > windowSeconds * 1000L) {
            spam = false;
        } else {
            String previous = prev.text().trim();
            if (current.equals(previous)) {
                spam = true;
            } else {
                spam = current.length() >= minLength
                        && TextUtil.similarity(previous, current) >= similarity;
            }
        }
        if (!spam) {
            lastSaid.put(id, new Said(now, message));
        }
        return spam;
    }

    /** 玩家退出时清掉记录。 */
    public void clear(UUID id) {
        lastSaid.remove(id);
    }
}

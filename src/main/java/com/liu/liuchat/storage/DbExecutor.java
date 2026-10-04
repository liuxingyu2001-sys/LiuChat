package com.liu.liuchat.storage;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 单线程数据库任务队列：所有 JDBC 读写都串行地跑在同一个工作线程上。
 * <ul>
 *   <li>{@link #write} 异步落库：命令路径（主线程）触发的写操作立即返回，
 *       不再阻塞主线程等 MySQL/SQLite 往返；同一 FIFO 队列保证写入顺序。</li>
 *   <li>{@link #read} 同步读取：提交的任务排在所有已入队写入之后执行，
 *       天然提供「读己之写」屏障（对账刷新不会漏掉尚未落盘的禁言）。</li>
 *   <li>工作线程在任务体内<b>不得</b>再调用 {@link #read}（单线程会自锁）；
 *       需要读写组合的原子操作应合并成一个任务。</li>
 *   <li>{@link #close} 先排空队列再关闭连接，停服不丢写入。</li>
 * </ul>
 * 任务体（数据库裸实现）自行捕获并记录异常，正常情况下不会向外抛出。
 */
final class DbExecutor {

    private final ExecutorService worker;
    private volatile boolean closed;

    DbExecutor(String threadName) {
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 异步写：不等待完成，任务按提交顺序串行执行；关闭后静默丢弃（停服流程已排空）。 */
    void write(Runnable task) {
        if (closed) return;
        try {
            worker.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    // 裸实现理论上自带捕获；这里兜底避免工作线程静默死亡
                    Logger.getLogger("LiuChat").log(Level.SEVERE, "数据库写任务失败", e);
                }
            });
        } catch (RejectedExecutionException e) {
            // 与 close() 竞争：队列已排空，丢弃该写入并由调用方日志可见
            closed = true;
        }
    }

    /**
     * 同步读：等待该任务完成并返回结果，之前入队的写入全部先行执行。
     * 等待期间被中断时不放弃任务（数据库任务很短）：清除中断位继续等，
     * 返回前恢复中断状态，避免带着中断位自旋。
     */
    <T> T read(Callable<T> task) {
        if (closed) {
            // 关闭后直接在调用线程执行裸任务：连接已断时任务内部会走 isReady=false 分支
            return call(task);
        }
        Future<T> future;
        try {
            future = worker.submit(task);
        } catch (RejectedExecutionException e) {
            // 与 close() 竞争：提交时线程池已关闭 → 降级为调用线程内联执行
            return call(task);
        }
        boolean interrupted = false;
        while (true) {
            try {
                T result = future.get();
                if (interrupted) Thread.currentThread().interrupt();
                return result;
            } catch (InterruptedException e) {
                // InterruptedException 已清除中断位；不中断数据库任务，继续等待
                interrupted = true;
            } catch (ExecutionException e) {
                if (interrupted) Thread.currentThread().interrupt();
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("数据库任务失败", cause);
            }
        }
    }

    private static <T> T call(Callable<T> task) {
        try {
            return task.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("数据库任务失败", e);
        }
    }

    /** 排空队列并关闭工作线程；返回是否在超时内全部落盘。幂等。 */
    boolean close(long awaitSeconds) {
        if (closed) return true;
        closed = true;
        worker.shutdown();
        try {
            if (worker.awaitTermination(awaitSeconds, TimeUnit.SECONDS)) {
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        worker.shutdownNow();
        return false;
    }

    /** 供测试观察：工作线程是否已终止（close 之后排空完成）。 */
    boolean isQuiescent() {
        return worker.isTerminated();
    }
}

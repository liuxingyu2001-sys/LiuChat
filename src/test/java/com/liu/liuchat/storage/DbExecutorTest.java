package com.liu.liuchat.storage;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据库任务队列语义：串行顺序、读己之写屏障、排空关闭、
 * 中断不放弃任务。存储层异步化的正确性靠这些保证。
 */
class DbExecutorTest {

    @Test
    void writesRunInOrderOnSingleWorkerThread() {
        DbExecutor executor = new DbExecutor("test-db-writer");
        List<Integer> order = new ArrayList<>();
        List<String> threads = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            int value = i;
            executor.write(() -> {
                order.add(value);
                threads.add(Thread.currentThread().getName());
            });
        }
        // read 是屏障：先于它提交的 50 个写必须全部完成
        int marker = executor.read(() -> 42);
        assertEquals(42, marker);
        assertEquals(50, order.size());
        for (int i = 0; i < 50; i++) assertEquals(i, order.get(i));
        assertEquals(1, threads.stream().distinct().count(), "必须始终是同一个工作线程");
        assertTrue(executor.close(5));
    }

    @Test
    void readSeesWritesQueuedBeforeIt() {
        DbExecutor executor = new DbExecutor("test-db-writer");
        AtomicReference<String> stored = new AtomicReference<>();
        executor.write(() -> stored.set("saved"));
        String seen = executor.read(stored::get);
        assertEquals("saved", seen);
        assertTrue(executor.close(5));
    }

    @Test
    void closeDrainsQueuedWritesAndIsIdempotent() {
        DbExecutor executor = new DbExecutor("test-db-writer");
        List<Integer> flushed = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            int value = i;
            executor.write(() -> flushed.add(value));
        }
        assertTrue(executor.close(5), "close 应在超时内排空队列");
        assertEquals(20, flushed.size(), "停服前排队的写入必须全部落盘");
        assertTrue(executor.close(5), "重复 close 幂等");
        assertTrue(executor.isQuiescent());
    }

    @Test
    void writeAfterCloseIsDroppedWithoutException() {
        DbExecutor executor = new DbExecutor("test-db-writer");
        assertTrue(executor.close(5));
        AtomicBoolean ran = new AtomicBoolean();
        executor.write(() -> ran.set(true)); // 不应抛 RejectedExecutionException
        assertFalse(ran.get());
        // 关闭后的读在调用线程内联执行（供仍尝试读取的调用方安全返回）
        assertEquals(7, executor.read(() -> 7));
    }

    @Test
    void workerSurvivesTaskException() {
        DbExecutor executor = new DbExecutor("test-db-writer");
        executor.write(() -> { throw new IllegalStateException("boom"); });
        AtomicBoolean after = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);
        executor.write(() -> { after.set(true); done.countDown(); });
        try {
            assertTrue(done.await(5, TimeUnit.SECONDS), "工作线程不应被任务异常杀死");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        assertTrue(after.get());
        assertTrue(executor.close(5));
    }

    @Test
    void readPropagatesRuntimeExceptionFromTask() {
        DbExecutor executor = new DbExecutor("test-db-writer");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> executor.read(() -> { throw new IllegalStateException("read boom"); }));
        assertEquals("read boom", error.getMessage());
        // 一次读失败不破坏队列
        assertEquals(1, executor.read(() -> 1));
        assertTrue(executor.close(5));
    }

    @Test
    void interruptedReadStillCompletesTaskAndRestoresFlag() {
        DbExecutor executor = new DbExecutor("test-db-writer");
        AtomicBoolean completed = new AtomicBoolean();
        executor.write(() -> {
            try {
                Thread.sleep(150);
                completed.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread.currentThread().interrupt();
        try {
            int value = executor.read(() -> 5);
            assertEquals(5, value);
            assertTrue(completed.get(), "中断不得放弃已提交的数据库任务");
            assertTrue(Thread.interrupted(), "等待结束后应恢复中断状态（并顺手清除）");
        } finally {
            Thread.interrupted(); // 不把中断位留给后续用例
        }
        assertTrue(executor.close(5));
    }
}

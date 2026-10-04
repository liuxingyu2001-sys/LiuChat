package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RESP 编解码单测 + 假 Redis 服务器集成测试。
 * <p>
 * Redis 是可选传输层，但协议实现是手写的——这里把编解码、订阅确认、
 * 发布回包、失败冷却、关闭清理全部覆盖；不依赖真实 Redis。
 */
class RedisBusTest {

    private static Logger silentLogger() {
        Logger logger = Logger.getLogger("RedisBusTest." + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    // ---------------- RESP 编码 ----------------

    @Test
    void encodesCommandAsArrayOfBulkStrings() {
        byte[] payload = {0x00, '\r', '\n', (byte) 0xFF};
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.writeBytes(bytes("*3\r\n$7\r\nPUBLISH\r\n$7\r\nliuchat\r\n$4\r\n"));
        expected.writeBytes(payload);
        expected.writeBytes(bytes("\r\n"));

        assertArrayEquals(expected.toByteArray(),
                RedisBus.command("PUBLISH", "liuchat", payload));
    }

    @Test
    void encodesPingCommand() {
        assertArrayEquals(bytes("*1\r\n$4\r\nPING\r\n"), RedisBus.command("PING"));
    }

    // ---------------- RESP 解码 ----------------

    @Test
    void readsSimpleStringIntegerAndError() throws IOException {
        assertEquals("OK", RedisBus.readReply(RedisBus.streamOf("+OK\r\n")));
        assertEquals(42L, RedisBus.readReply(RedisBus.streamOf(":42\r\n")));
        IOException error = assertThrows(IOException.class,
                () -> RedisBus.readReply(RedisBus.streamOf("-ERR unknown command\r\n")));
        assertTrue(error.getMessage().contains("unknown command"));
    }

    @Test
    void readsBulkStringIncludingBinaryAndCrlf() throws IOException {
        byte[] payload = {'a', '\r', '\n', 'b', (byte) 0xFF};
        byte[] wire = concat(bytes("$5\r\n"), payload, bytes("\r\n"));
        assertArrayEquals(payload, (byte[]) RedisBus.readReply(RedisBus.streamOf(wire)));
        assertNull(RedisBus.readReply(RedisBus.streamOf("$-1\r\n")));
    }

    @Test
    void readsNestedArraysAndEmptyArray() throws IOException {
        Object[] frame = assertInstanceOf(Object[].class,
                RedisBus.readReply(RedisBus.streamOf("*3\r\n$7\r\nmessage\r\n$7\r\nliuchat\r\n$2\r\nhi\r\n")));
        assertEquals(3, frame.length);
        assertArrayEquals(bytes("message"), (byte[]) frame[0]);
        assertArrayEquals(bytes("hi"), (byte[]) frame[2]);
        // 空数组约定返回 null（订阅/PING 的帧至少 2 个元素，不受影响）
        assertNull(RedisBus.readReply(RedisBus.streamOf("*0\r\n")));
    }

    @Test
    void rejectsMalformedAndTruncatedReplies() {
        assertThrows(EOFException.class, () -> RedisBus.readReply(RedisBus.streamOf(new byte[0])));
        assertThrows(IOException.class, () -> RedisBus.readReply(RedisBus.streamOf("\r\n")));
        // bulk 声明 5 字节但只有 2 字节
        assertThrows(EOFException.class, () -> RedisBus.readReply(RedisBus.streamOf(bytes("$5\r\nab\r\n"))));
        // 类型行是乱码
        assertThrows(IOException.class, () -> RedisBus.readReply(RedisBus.streamOf(bytes("#123\r\n"))));
    }

    // ---------------- 集成：订阅 + 发布 + 关闭 ----------------

    @Test
    void subscribesReceivesAndPublishesAgainstFakeServer() throws Exception {
        byte[] frame = CrossServerCodec.stripForward(
                CrossServerCodec.encodeChat("lobby", "uuid-1", "Notch", "hello", "", "", ""));
        try (FakeRedis fake = new FakeRedis(frame)) {
            AtomicReference<byte[]> received = new AtomicReference<>();
            CountDownLatch gotMessage = new CountDownLatch(1);
            RedisBus bus = new RedisBus(fake.settings(), payload -> {
                received.set(payload);
                gotMessage.countDown();
            }, silentLogger());
            bus.start();
            try {
                // 订阅确认后服务器随即推送一帧 → 订阅线程应分发给 listener
                assertTrue(fake.subscribed.await(5, TimeUnit.SECONDS), "订阅未送达假服务器");
                assertTrue(gotMessage.await(5, TimeUnit.SECONDS), "订阅线程未收到推送帧");
                assertArrayEquals(frame, received.get());
                // 发布：应答 :1 → true，且假服务器收到的载荷与发送一致
                assertTrue(bus.publish(frame), "publish 应在假服务器应答 :1 后返回 true");
                assertTrue(fake.published.await(5, TimeUnit.SECONDS));
                assertArrayEquals(frame, fake.lastPublished.get());
            } finally {
                bus.close();
            }
            assertFalse(bus.isSubscribed(), "close 后订阅连接应已释放");
        }
    }

    @Test
    void publishFailsFastDuringCooldownWhenServerDown() throws Exception {
        // 占一个端口再立即释放 → 得到一个必然拒绝连接的端口
        ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        int deadPort = probe.getLocalPort();
        probe.close();

        byte[] frame = CrossServerCodec.stripForward(
                CrossServerCodec.encodeChat("lobby", "u", "n", "m", "", "", ""));
        RedisBus bus = new RedisBus(
                new RedisBus.Settings("127.0.0.1", deadPort, "", 0, "liuchat"),
                payload -> { }, silentLogger());
        bus.start();
        try {
            assertFalse(bus.publish(frame), "连不上的 Redis 必须返回 false 以回落代理");
            long start = System.nanoTime();
            assertFalse(bus.publish(frame), "冷却期内应立即失败");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMs < 500, "冷却期内的 publish 应瞬时返回，实际耗时 " + elapsedMs + "ms");
        } finally {
            bus.close();
        }
    }

    @Test
    void publishTreatsZeroReceiversAsFailure() throws Exception {
        // :0 = 连本服自己的订阅都不在 → 不可信，必须回落代理
        try (FakeRedis fake = new FakeRedis(new byte[0], 0)) {
            RedisBus bus = new RedisBus(fake.settings(), payload -> { }, silentLogger());
            bus.start();
            try {
                assertFalse(bus.publish(new byte[]{1, 2, 3}), ":0 应答必须视为失败");
            } finally {
                bus.close();
            }
        }
    }

    @Test
    void closeIsIdempotentAndStopsSubscriber() throws Exception {
        try (FakeRedis fake = new FakeRedis(new byte[0])) {
            RedisBus bus = new RedisBus(fake.settings(), payload -> { }, silentLogger());
            bus.start();
            assertTrue(fake.subscribed.await(5, TimeUnit.SECONDS));
            bus.close();
            bus.close(); // 幂等
            assertFalse(bus.isSubscribed());
        }
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (byte[] part : parts) bytes.writeBytes(part);
        return bytes.toByteArray();
    }

    // ---------------- 假 Redis 服务器 ----------------

    /**
     * 极简 RESP 服务器：处理 SUBSCRIBE/PUBLISH/PING/AUTH/SELECT，
     * 订阅确认后立即（可选）推送一帧，用于验证完整收发链路。
     */
    private static final class FakeRedis implements AutoCloseable {
        private final ServerSocket server;
        private final List<Thread> workers = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch subscribed = new CountDownLatch(1);
        final CountDownLatch published = new CountDownLatch(1);
        final AtomicReference<byte[]> lastPublished = new AtomicReference<>();
        private final byte[] pushAfterSubscribe;
        private final long publishReceivers;

        FakeRedis(byte[] pushAfterSubscribe) throws IOException {
            this(pushAfterSubscribe, 1);
        }

        FakeRedis(byte[] pushAfterSubscribe, long publishReceivers) throws IOException {
            this.pushAfterSubscribe = pushAfterSubscribe;
            this.publishReceivers = publishReceivers;
            this.server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(this::acceptLoop, "fake-redis-accept");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        RedisBus.Settings settings() {
            return new RedisBus.Settings("127.0.0.1", server.getLocalPort(), "", 0, "liuchat");
        }

        private void acceptLoop() {
            try {
                while (!server.isClosed()) {
                    Socket socket = server.accept();
                    Thread worker = new Thread(() -> handle(socket), "fake-redis-worker");
                    worker.setDaemon(true);
                    workers.add(worker);
                    worker.start();
                }
            } catch (IOException ignored) {
                // close() 关闭监听套接字会走到这里
            }
        }

        private void handle(Socket socket) {
            try (socket) {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                while (true) {
                    Object parsed = RedisBus.readReply(in);
                    if (!(parsed instanceof Object[] command) || command.length == 0
                            || !(command[0] instanceof byte[] rawName)) {
                        return; // 非法命令：断开
                    }
                    String name = new String(rawName, StandardCharsets.US_ASCII);
                    switch (name) {
                        case "SUBSCRIBE" -> {
                            write(out, RedisBus.command("subscribe", "liuchat", "1"));
                            subscribed.countDown();
                            if (pushAfterSubscribe.length > 0) {
                                write(out, RedisBus.command("message", "liuchat", pushAfterSubscribe));
                            }
                        }
                        case "PUBLISH" -> {
                            lastPublished.set((byte[]) command[2]);
                            write(out, bytes(":" + publishReceivers + "\r\n"));
                            published.countDown();
                        }
                        case "PING" -> write(out, RedisBus.command("pong", ""));
                        case "AUTH", "SELECT" -> write(out, bytes("+OK\r\n"));
                        default -> write(out, bytes("-ERR unknown command\r\n"));
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // 客户端关闭连接
            }
        }

        private static void write(DataOutputStream out, byte[] data) throws IOException {
            out.write(data);
            out.flush();
        }

        @Override
        public void close() throws IOException {
            server.close();
            synchronized (workers) {
                for (Thread worker : workers) {
                    worker.interrupt();
                }
            }
        }
    }
}

package com.liu.liuchat.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.function.Consumer;

/**
 * Redis pub/sub 传输层（可选）：跨服消息的第二条链路。
 * <p>
 * 存在的意义是补代理转发的两个盲区：
 * <ul>
 *   <li>Velocity 只向<b>有玩家在线</b>的子服投递插件消息，空服收不到；</li>
 *   <li>plugin messaging 发送必须借一个在线玩家当载体（{@code sendViaAny} 的限制）。</li>
 * </ul>
 * Redis 订阅常驻，空服也能收发，且发送不依赖任何玩家在线。
 * <p>
 * 实现：手写 RESP2 协议（AUTH/SELECT/SUBSCRIBE/PUBLISH/PING），零第三方依赖，
 * 与插件「不依赖第三方框架」的定位一致。两条独立连接：
 * <ul>
 *   <li>订阅连接：专用守护线程阻塞读，断线指数退避重连（1s → 30s），
 *       空闲 60 秒发 PING 探活，10 秒无回包判定死链并重连；</li>
 *   <li>发布连接：懒建立，发布失败进入 5 秒冷却（冷却期内快速返回 false，
 *       走代理回落，不让主线程反复付连接成本），冷却过后自动重连。</li>
 * </ul>
 * 失败语义：{@link #publish} 返回 false = 本条消息交给调用方回落代理转发；
 * 订阅断开期间收不到 Redis 消息，但代理链路（若可用）照常工作，链路互为兜底。
 * <p>
 * 载荷是 {@link CrossServerCodec#stripForward} 产出的帧，接收侧直接走
 * {@link CrossServerCodec#decodeInbound}，协议与代理链路完全一致，无协议号变更。
 * 线程安全：{@code publish} 可从主线程调用；{@code listener} 在订阅线程回调，
 * 回调方负责切回需要的线程。
 */
public final class RedisBus implements AutoCloseable {

    /** 连接/应答超时：与子服同机房的 Redis 通常 <5ms，超时即视为链路异常 */
    private static final int IO_TIMEOUT_MS = 1000;
    /** 订阅稳态读超时（到时发 PING 探活） */
    private static final int SUBSCRIBE_IDLE_TIMEOUT_MS = 60_000;
    /** PING 探活等待回包上限 */
    private static final int PING_TIMEOUT_MS = 10_000;
    /** 发布失败冷却：存失败时刻（nanoTime 差值判断，规避 nanoTime 可为负的符号陷阱） */
    private static final long NO_COOLDOWN = Long.MIN_VALUE;
    private static final long PUBLISH_COOLDOWN_MS = 5_000;
    private static final long BACKOFF_INITIAL_MS = 1_000;
    private static final long BACKOFF_MAX_MS = 30_000;
    /** 控制行（类型行/简单串）读取上限，防畸形数据撑爆内存 */
    private static final int MAX_LINE_BYTES = 64 * 1024;

    public record Settings(String host, int port, String password, int db, String channel) {
        public Settings {
            if (host == null || host.isBlank()) host = "127.0.0.1";
            if (channel == null || channel.isBlank()) channel = "liuchat";
            if (port < 1 || port > 65535) port = 6379;
            if (db < 0) db = 0;
            password = password == null ? "" : password;
        }
    }

    private final Settings settings;
    private final Consumer<byte[]> listener;
    private final Logger log;

    private volatile boolean running;
    private volatile Socket subscriberSocket;
    private Thread subscriberThread;
    private final AtomicBoolean warned = new AtomicBoolean();
    private final AtomicBoolean announced = new AtomicBoolean();

    /** 发布连接及其锁（发布 = 写命令 + 读应答，必须独占） */
    private final Object pubLock = new Object();
    private Socket pubSocket;
    private DataInputStream pubIn;
    private DataOutputStream pubOut;
    /** 上次发布失败的时刻（nanoTime）；{@link #NO_COOLDOWN} = 无冷却 */
    private volatile long failedAtNanos = NO_COOLDOWN;

    public RedisBus(Settings settings, Consumer<byte[]> listener, Logger log) {
        this.settings = settings;
        this.listener = listener;
        this.log = log;
    }

    /** 启动订阅线程（异步连接，不阻塞调用方）。幂等。 */
    public void start() {
        if (running) return;
        running = true;
        subscriberThread = new Thread(this::subscribeLoop, "LiuChat-redis-sub");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    /** 订阅线程当前是否处于已订阅状态（供日志/诊断）。 */
    public boolean isSubscribed() {
        Socket socket = subscriberSocket;
        return running && socket != null && !socket.isClosed();
    }

    /**
     * 发布一帧（{@link CrossServerCodec#stripForward} 产出的接收形态）。
     *
     * @return true = Redis 已确认收下；false = 不可用/失败，调用方应回落代理转发
     */
    public boolean publish(byte[] frame) {
        if (!running || frame == null) return false;
        long failedAt = failedAtNanos;
        if (failedAt != NO_COOLDOWN && System.nanoTime() - failedAt < PUBLISH_COOLDOWN_MS * 1_000_000L) {
            return false; // 冷却期内直接回落代理，不让主线程反复付连接成本
        }
        synchronized (pubLock) {
            if (!running) return false; // 等锁期间可能已 close()，不再重建连接
            try {
                if (pubOut == null) openPublisher();
                pubOut.write(command("PUBLISH", settings.channel(), frame));
                pubOut.flush();
                Object reply = readReply(pubIn);
                if (reply instanceof Long receivers && receivers > 0) {
                    return true;
                }
                // :0 = 连本服自己的订阅都不在（订阅连接断了而发布连接还活着）→ 不可信，回落代理。
                // 本服订阅在线时 PUBLISH 至少会计入自己，正常部署不会走到这里。
                failedAtNanos = System.nanoTime();
            } catch (IOException e) {
                closePublisher();
                markPublishFailure(e);
            }
        }
        return false;
    }

    private void markPublishFailure(IOException error) {
        failedAtNanos = System.nanoTime();
        if (error != null) warnOnce(error);
    }

    @Override
    public void close() {
        running = false;
        synchronized (pubLock) {
            closePublisher();
        }
        Socket subscriber = subscriberSocket;
        if (subscriber != null) {
            try {
                subscriber.close(); // 阻塞读抛出异常，线程退出
            } catch (IOException ignored) {
                // 关闭失败无须处理
            }
        }
        Thread thread = subscriberThread;
        if (thread != null) {
            thread.interrupt(); // 唤醒退避 sleep，立即退出（阻塞读已由关闭 socket 打断）
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ---------------- 订阅线程 ----------------

    private void subscribeLoop() {
        long backoffMs = BACKOFF_INITIAL_MS;
        while (running) {
            try (Socket socket = connect()) {
                subscriberSocket = socket;
                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                socket.setSoTimeout(IO_TIMEOUT_MS); // 认证/订阅确认都不允许无限期阻塞
                authenticate(in, out);
                out.write(command("SUBSCRIBE", settings.channel()));
                out.flush();
                // 等订阅确认（期间的 message 帧也正常分发）
                boolean confirmed = false;
                while (running && !confirmed) {
                    Object[] frame = readArray(in);
                    if (isType(frame, "subscribe")) {
                        confirmed = true;
                    } else {
                        dispatch(frame);
                    }
                }
                if (!running) break;
                if (announced.compareAndSet(false, true)) {
                    log.info("Redis 跨服订阅已连接: " + settings.host() + ":" + settings.port()
                            + "/" + settings.db() + " ch=" + settings.channel());
                }
                backoffMs = BACKOFF_INITIAL_MS;
                socket.setSoTimeout(SUBSCRIBE_IDLE_TIMEOUT_MS);
                while (running) {
                    Object[] frame;
                    try {
                        frame = readArray(in);
                    } catch (SocketTimeoutException idle) {
                        // 空闲太久：PING 探活，限期内无回包 → 抛出走重连
                        socket.setSoTimeout(PING_TIMEOUT_MS);
                        out.write(command("PING"));
                        out.flush();
                        frame = readArray(in);
                        socket.setSoTimeout(SUBSCRIBE_IDLE_TIMEOUT_MS);
                    }
                    dispatch(frame);
                }
            } catch (IOException | RuntimeException e) {
                if (running) warnOnce(e);
            } finally {
                subscriberSocket = null;
            }
            if (!running) break;
            backoffSleep(backoffMs);
            backoffMs = Math.min(backoffMs * 2, BACKOFF_MAX_MS);
        }
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(settings.host(), settings.port()), IO_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            return socket;
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 关闭失败无须处理
            }
            throw e;
        }
    }

    private void authenticate(DataInputStream in, DataOutputStream out) throws IOException {
        if (!settings.password().isEmpty()) {
            out.write(command("AUTH", settings.password()));
            out.flush();
            expectOk(readReply(in));
        }
        if (settings.db() > 0) {
            out.write(command("SELECT", String.valueOf(settings.db())));
            out.flush();
            expectOk(readReply(in));
        }
    }

    private static void expectOk(Object reply) throws IOException {
        if (!"OK".equals(reply)) {
            throw new IOException("Redis 认证/选库失败: " + describe(reply));
        }
    }

    private void dispatch(Object[] frame) {
        if (frame == null || frame.length < 3 || !isType(frame, "message")) return;
        if (!(frame[2] instanceof byte[] payload)) return;
        try {
            listener.accept(payload);
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Redis 消息处理失败", e);
        }
    }

    private static boolean isType(Object[] frame, String type) {
        return frame != null && frame.length >= 1 && type.equals(ascii(frame[0]));
    }

    private static String ascii(Object value) {
        return value instanceof byte[] bytes ? new String(bytes, StandardCharsets.US_ASCII) : null;
    }

    private void warnOnce(Exception e) {
        if (warned.compareAndSet(false, true)) {
            log.log(Level.WARNING, "Redis 连接异常（跨服消息临时走代理回落，后台自动重连）: "
                    + settings.host() + ":" + settings.port(), e);
        } else if (log.isLoggable(Level.FINE)) {
            log.log(Level.FINE, "Redis 连接异常（重试中）", e);
        }
    }

    private void backoffSleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    // ---------------- 发布连接 ----------------

    private void openPublisher() throws IOException {
        Socket socket = connect();
        DataInputStream in = null;
        DataOutputStream out = null;
        try {
            in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());
            socket.setSoTimeout(IO_TIMEOUT_MS);
            if (!settings.password().isEmpty()) {
                out.write(command("AUTH", settings.password()));
                out.flush();
                expectOk(readReply(in));
            }
            if (settings.db() > 0) {
                out.write(command("SELECT", String.valueOf(settings.db())));
                out.flush();
                expectOk(readReply(in));
            }
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 关闭失败无须处理
            }
            throw e;
        }
        pubSocket = socket;
        pubIn = in;
        pubOut = out;
        failedAtNanos = NO_COOLDOWN;
    }

    private void closePublisher() {
        if (pubSocket != null) {
            try {
                pubSocket.close();
            } catch (IOException ignored) {
                // 关闭失败无须处理
            }
        }
        pubSocket = null;
        pubIn = null;
        pubOut = null;
    }

    // ---------------- RESP 编解码（静态部分可单测） ----------------

    /** 编码一条 RESP 命令：元素为 String（UTF-8）或 byte[]（二进制安全）。 */
    static byte[] command(Object... parts) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write('*');
        writeAscii(bytes, String.valueOf(parts.length));
        bytes.write('\r');
        bytes.write('\n');
        for (Object part : parts) {
            byte[] data = part instanceof byte[] binary ? binary
                    : String.valueOf(part).getBytes(StandardCharsets.UTF_8);
            bytes.write('$');
            writeAscii(bytes, String.valueOf(data.length));
            bytes.write('\r');
            bytes.write('\n');
            bytes.write(data, 0, data.length);
            bytes.write('\r');
            bytes.write('\n');
        }
        return bytes.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String text) {
        byte[] data = text.getBytes(StandardCharsets.US_ASCII);
        out.write(data, 0, data.length);
    }

    /**
     * 读取一个 RESP 应答（递归）。
     *
     * @return String（简单串）/ Long（整数）/ byte[]（bulk，null = 空值）/ Object[]（数组）/ null（空数组）
     * @throws IOException 协议错误、错误应答（-ERR…）或连接断开
     */
    static Object readReply(DataInputStream in) throws IOException {
        int type = in.read();
        if (type < 0) throw new EOFException("Redis 连接已关闭");
        switch (type) {
            case '+' -> {
                return readLine(in);
            }
            case '-' -> {
                throw new IOException("Redis 错误应答: " + readLine(in));
            }
            case ':' -> {
                return Long.parseLong(readLine(in));
            }
            case '$' -> {
                int length = Integer.parseInt(readLine(in));
                if (length == -1) return null;
                if (length < 0 || length > 512 * 1024 * 1024) throw new IOException("非法 bulk 长度: " + length);
                byte[] data = in.readNBytes(length);
                if (data.length != length) throw new EOFException("Redis bulk 数据不完整");
                expectCrlf(in);
                return data;
            }
            case '*' -> {
                int length = Integer.parseInt(readLine(in));
                if (length == -1) return null;
                if (length < 0 || length > 1024 * 1024) throw new IOException("非法数组长度: " + length);
                if (length == 0) return null;
                Object[] items = new Object[length];
                for (int i = 0; i < length; i++) items[i] = readReply(in);
                return items;
            }
            default -> throw new IOException("非法 RESP 类型字节: " + type);
        }
    }

    /** 读取一个数组应答；非数组（如超时前的错误串）视为协议错误。 */
    private static Object[] readArray(DataInputStream in) throws IOException {
        Object reply = readReply(in);
        if (reply instanceof Object[] items) return items;
        throw new IOException("期待数组应答，实际: " + describe(reply));
    }

    private static String readLine(DataInputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(64);
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                byte[] raw = line.toByteArray();
                int length = raw.length;
                if (length > 0 && raw[length - 1] == '\r') length--;
                return new String(raw, 0, length, StandardCharsets.US_ASCII);
            }
            line.write(b);
            if (line.size() > MAX_LINE_BYTES) throw new IOException("RESP 行过长");
        }
        throw new EOFException("Redis 连接已关闭");
    }

    private static void expectCrlf(DataInputStream in) throws IOException {
        if (in.read() != '\r' || in.read() != '\n') throw new IOException("RESP bulk 缺少 CRLF 结尾");
    }

    private static String describe(Object reply) {
        if (reply == null) return "null";
        if (reply instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        return String.valueOf(reply);
    }

    /** 供测试：把 RESP 字节流作为内存流读取。 */
    static DataInputStream streamOf(byte[] data) {
        return new DataInputStream(new ByteArrayInputStream(data));
    }

    /** 供测试：ASCII 文本应答的便捷入口。 */
    static DataInputStream streamOf(String data) {
        return streamOf(data.getBytes(StandardCharsets.US_ASCII));
    }
}

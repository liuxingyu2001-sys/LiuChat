package com.liu.liuchat.hook.qq;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * QQ 官方机器人 WebSocket 客户端（JDK 自带 HttpClient / WebSocket，零第三方依赖）。
 *
 * <p>负责：获取 AppAccessToken、请求网关、identify/resume、心跳、重连退避，
 * 以及通过 HTTP API 发送群聊/私聊/频道消息。所有网络工作都在本类自己的守护线程上，
 * 不会阻塞服务器主线程。
 *
 * <p>协议要点：
 * <ul>
 *   <li>{@code op 10} HELLO 携带 heartbeat_interval；随后发 {@code op 2} identify</li>
 *   <li>{@code op 0 READY/RESUMED} 表示可用；{@code op 11} 为心跳 ACK</li>
 *   <li>{@code op 7} 要求重连（保留 session 走 resume）；{@code op 9} 会话失效（重新 identify）</li>
 * </ul>
 */
public final class QqBotClient implements AutoCloseable {

    private static final String TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken";
    private static final String PROD_API = "https://api.sgroup.qq.com";
    private static final String SANDBOX_API = "https://sandbox.api.sgroup.qq.com";
    private static final String PROD_GATEWAY = "wss://api.sgroup.qq.com/websocket/";
    private static final String SANDBOX_GATEWAY = "wss://sandbox.api.sgroup.qq.com/websocket/";
    private static final int MAX_FRAME_CHARS = 1 << 20;

    /** 业务事件回调（在 HTTP 客户端线程上调用，实现方需自行切回主线程）。 */
    public interface Listener {
        void onEvent(QqBotEvent event);
    }

    public record SendResult(boolean ok, int status, String detail, long retryAfterMs) {
        static SendResult failure(String detail) {
            return new SendResult(false, 0, detail, 0);
        }
    }

    private final QqBotSettings settings;
    private final Listener listener;
    private final Logger logger;
    private final HttpClient http;
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;
    private final Object stateLock = new Object();
    private final Object sendLock = new Object();
    private final AtomicBoolean reconnecting = new AtomicBoolean();

    private volatile boolean closed;
    private volatile WebSocket socket;
    private volatile int generation;
    private volatile int reconnectGeneration;
    private volatile boolean ready;
    private volatile String status = "stopped";
    private volatile String accessToken;
    private volatile long tokenExpiresAt;
    private volatile String sessionId;
    private volatile int lastSeq = -1;
    private volatile int heartbeatIntervalMs = 30_000;
    private volatile long lastHeartbeatAck;
    private volatile ScheduledFuture<?> heartbeat;
    private volatile int reconnectAttempts;
    private volatile long lastSendWarnAt;

    public QqBotClient(QqBotSettings settings, Listener listener, Logger logger) {
        this.settings = settings;
        this.listener = listener;
        this.logger = logger;
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "LiuChat-QQ-WebSocket");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "LiuChat-QQ");
            thread.setDaemon(true);
            return thread;
        });
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(settings.timeoutSeconds()))
                .executor(executor)
                .build();
    }

    public void start() {
        if (closed) return;
        execute(this::connect);
    }

    private void execute(Runnable task) {
        try {
            scheduler.execute(task);
        } catch (RuntimeException ignored) {
            // 调度器已关闭（插件停用）时忽略
        }
    }

    public boolean isReady() {
        return ready;
    }

    public String status() {
        return status;
    }

    // ---------------- 连接与重连 ----------------

    private void connect() {
        if (closed) return;
        final int gen;
        synchronized (stateLock) {
            if (closed) return;
            gen = ++generation;
            ready = false;
        }
        setStatus("connecting");
        try {
            String token = accessToken();
            String gateway = gatewayUrl(token);
            logger.info("QQ 机器人正在连接网关: " + gateway);
            WebSocket webSocket = http.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(settings.timeoutSeconds()))
                    .buildAsync(URI.create(gateway), new SocketListener(gen))
                    .get(settings.timeoutSeconds() + 5L, TimeUnit.SECONDS);
            synchronized (stateLock) {
                if (closed || gen != generation) {
                    webSocket.abort();
                    return;
                }
                socket = webSocket;
            }
            scheduler.schedule(() -> {
                if (closed || gen != generation || ready) return;
                logger.warning("QQ 机器人等待网关握手超时");
                scheduleReconnect(gen, "等待网关握手超时", true);
            }, settings.timeoutSeconds(), TimeUnit.SECONDS);
        } catch (Exception error) {
            scheduleReconnect(gen, "连接失败: " + message(error), true);
        }
    }

    /** 手动重连（/liuc reload 或调试用）。 */
    public void reconnect() {
        if (closed) return;
        WebSocket old;
        synchronized (stateLock) {
            generation++;
            reconnectGeneration++;
            old = socket;
            socket = null;
            ready = false;
            sessionId = null;
            lastSeq = -1;
            cancelHeartbeatLocked();
        }
        abort(old);
        reconnecting.set(false);
        reconnectAttempts = 0;
        execute(this::connect);
    }

    private void scheduleReconnect(int gen, String reason, boolean resume) {
        if (closed) return;
        WebSocket old;
        final int attempt;
        final long delay;
        final int token;
        synchronized (stateLock) {
            if (closed || gen != generation) return;
            if (!reconnecting.compareAndSet(false, true)) return;
            generation++;
            token = ++reconnectGeneration;
            old = socket;
            socket = null;
            ready = false;
            if (!resume) {
                sessionId = null;
                lastSeq = -1;
            }
            cancelHeartbeatLocked();
            attempt = ++reconnectAttempts;
            long base = settings.reconnectSeconds();
            long max = settings.reconnectMaxSeconds();
            delay = Math.min(max, base * (1L << Math.min(attempt - 1, 10)));
        }
        abort(old);
        setStatus("reconnecting");
        logger.warning("QQ 机器人连接断开（" + reason + "），" + delay + " 秒后重连");
        try {
            scheduler.schedule(() -> {
                synchronized (stateLock) {
                    if (token != reconnectGeneration) return;
                }
                reconnecting.set(false);
                if (!closed) connect();
            }, delay, TimeUnit.SECONDS);
        } catch (RuntimeException ignored) {
            // 调度器已关闭（插件停用）时忽略
        }
    }

    private void abort(WebSocket webSocket) {
        if (webSocket == null) return;
        try {
            webSocket.abort();
        } catch (RuntimeException ignored) {
            // 已经关闭的 socket 直接忽略
        }
    }

    private static void request(WebSocket webSocket) {
        try {
            webSocket.request(1);
        } catch (RuntimeException ignored) {
            // socket 已关闭时 request 可能失败，交由 onClose/onError 重连
        }
    }

    private void setStatus(String value) {
        status = value;
    }

    // ---------------- WebSocket 回调 ----------------

    private final class SocketListener implements WebSocket.Listener {
        private final int gen;
        private final StringBuilder buffer = new StringBuilder();

        private SocketListener(int gen) {
            this.gen = gen;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            request(webSocket);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (closed || gen != generation) {
                abort(webSocket);
                return null;
            }
            buffer.append(data);
            if (buffer.length() > MAX_FRAME_CHARS) {
                buffer.setLength(0);
                logger.warning("QQ 网关消息超过 " + MAX_FRAME_CHARS + " 字符，已丢弃");
                request(webSocket);
                return null;
            }
            if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                handle(gen, webSocket, text);
            }
            request(webSocket);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (!closed && gen == generation) {
                if (statusCode == 4013 || statusCode == 4014) {
                    logger.warning("QQ 网关拒绝了 intents（" + statusCode
                            + "）；请检查 qq.yml 的 intents 与机器人公域/私域类型");
                }
                boolean resume = statusCode != 4003 && statusCode != 4004
                        && statusCode != 4006 && statusCode != 4009;
                if (statusCode == 4003 || statusCode == 4004) invalidateToken();
                scheduleReconnect(gen, "WebSocket 关闭 " + statusCode
                        + (reason == null || reason.isBlank() ? "" : " " + reason), resume);
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (!closed && gen == generation) {
                scheduleReconnect(gen, "WebSocket 错误: " + message(error), true);
            }
        }
    }

    private void handle(int gen, WebSocket webSocket, String text) {
        if (closed || gen != generation) return;
        JsonObject root;
        try {
            root = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException error) {
            logger.log(Level.FINE, "忽略无法解析的 QQ 网关消息", error);
            return;
        }
        int op = intField(root, "op", -1);
        switch (op) {
            case 10 -> onHello(gen, webSocket, object(root, "d"));
            case 11 -> lastHeartbeatAck = System.currentTimeMillis();
            case 1 -> send(webSocket, heartbeatPayload());
            case 7 -> {
                logger.info("QQ 网关要求重连");
                scheduleReconnect(gen, "网关要求重连", true);
            }
            case 9 -> {
                logger.warning("QQ 会话失效，将重新 identify");
                synchronized (stateLock) {
                    sessionId = null;
                    lastSeq = -1;
                }
                scheduleReconnect(gen, "会话失效", false);
            }
            case 0 -> onDispatch(gen, root);
            default -> {
                // 其他 opcode 预留，忽略
            }
        }
    }

    private void onHello(int gen, WebSocket webSocket, JsonObject hello) {
        heartbeatIntervalMs = Math.max(1000, intField(hello, "heartbeat_interval", 30_000));
        startHeartbeat(gen);
        final String token;
        try {
            token = accessToken();
        } catch (Exception error) {
            scheduleReconnect(gen, "获取 access_token 失败: " + message(error), true);
            return;
        }
        if (sessionId != null && lastSeq >= 0) {
            send(webSocket, resumePayload(token));
        } else {
            send(webSocket, identifyPayload(token));
        }
    }

    private void onDispatch(int gen, JsonObject root) {
        if (root.has("s") && root.get("s").isJsonPrimitive()) {
            try {
                lastSeq = root.get("s").getAsInt();
            } catch (RuntimeException ignored) {
                // 序号只在 resume 时需要，解析失败不致命
            }
        }
        String type = stringField(root, "t");
        JsonObject data = object(root, "d");
        if ("READY".equals(type)) {
            synchronized (stateLock) {
                sessionId = stringField(data, "session_id");
            }
            ready = true;
            reconnectAttempts = 0;
            setStatus("ready");
            logger.info("QQ 机器人已连接（WebSocket READY" + (sessionId == null || sessionId.isBlank()
                    ? "" : ", session=" + sessionId) + "）");
            return;
        }
        if ("RESUMED".equals(type)) {
            ready = true;
            reconnectAttempts = 0;
            setStatus("ready");
            logger.info("QQ 机器人已恢复连接（RESUMED）");
            return;
        }
        QqBotEvent event = QqBotEventParser.parse(type, data);
        if (event == null) return;
        try {
            listener.onEvent(event);
        } catch (RuntimeException error) {
            logger.log(Level.WARNING, "处理 QQ 事件失败: " + type, error);
        }
    }

    // ---------------- 心跳 ----------------

    private void startHeartbeat(int gen) {
        synchronized (stateLock) {
            cancelHeartbeatLocked();
            lastHeartbeatAck = System.currentTimeMillis();
            long interval = Math.max(1000, heartbeatIntervalMs);
            try {
                heartbeat = scheduler.scheduleAtFixedRate(() -> {
                    if (closed || gen != generation || !ready) return;
                    long now = System.currentTimeMillis();
                    if (now - lastHeartbeatAck > interval * 2L) {
                        scheduleReconnect(gen, "心跳超时", true);
                        return;
                    }
                    WebSocket webSocket = socket;
                    if (webSocket != null) send(webSocket, heartbeatPayload());
                }, interval, interval, TimeUnit.MILLISECONDS);
            } catch (RuntimeException ignored) {
                // 调度器已关闭（插件停用）时忽略
            }
        }
    }

    private void cancelHeartbeatLocked() {
        ScheduledFuture<?> task = heartbeat;
        heartbeat = null;
        if (task != null) task.cancel(false);
    }

    private void send(WebSocket webSocket, String json) {
        if (webSocket == null || closed) return;
        synchronized (sendLock) {
            if (webSocket.isOutputClosed()) return;
            try {
                webSocket.sendText(json, true).whenComplete((ignored, error) -> {
                    if (error != null) logger.log(Level.FINE, "QQ WebSocket 发送失败", error);
                });
            } catch (RuntimeException error) {
                logger.log(Level.FINE, "QQ WebSocket 发送失败", error);
            }
        }
    }

    private String identifyPayload(String token) {
        JsonObject data = new JsonObject();
        data.addProperty("token", "QQBot " + token);
        data.addProperty("intents", settings.intents());
        JsonArray shard = new JsonArray();
        shard.add(0);
        shard.add(1);
        data.add("shard", shard);
        JsonObject properties = new JsonObject();
        properties.addProperty("$os", "linux");
        properties.addProperty("$browser", "liuchat");
        properties.addProperty("$device", "liuchat");
        data.add("properties", properties);
        return envelope(2, data).toString();
    }

    private String resumePayload(String token) {
        JsonObject data = new JsonObject();
        data.addProperty("token", "QQBot " + token);
        data.addProperty("session_id", sessionId);
        data.addProperty("seq", Math.max(0, lastSeq));
        return envelope(6, data).toString();
    }

    private String heartbeatPayload() {
        JsonObject root = new JsonObject();
        root.addProperty("op", 1);
        root.add("d", lastSeq < 0 ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(lastSeq));
        return root.toString();
    }

    private static JsonObject envelope(int op, JsonObject data) {
        JsonObject root = new JsonObject();
        root.addProperty("op", op);
        root.add("d", data);
        return root;
    }

    // ---------------- Token / 网关 ----------------

    private synchronized String accessToken() throws IOException, InterruptedException {
        long now = System.currentTimeMillis();
        if (accessToken != null && now < tokenExpiresAt) return accessToken;
        JsonObject body = new JsonObject();
        body.addProperty("appId", settings.appId());
        body.addProperty("clientSecret", settings.appSecret());
        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .timeout(Duration.ofSeconds(settings.timeoutSeconds()))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("获取 access_token 失败 HTTP " + response.statusCode() + " "
                    + truncate(response.body(), 200));
        }
        JsonObject json;
        try {
            json = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IOException("access_token 响应不是 JSON: " + truncate(response.body(), 200), error);
        }
        String token = stringField(json, "access_token");
        if (token.isEmpty()) {
            throw new IOException("access_token 响应缺少 access_token: " + truncate(response.body(), 200));
        }
        long expires = longField(json, "expires_in", 7200);
        accessToken = token;
        tokenExpiresAt = System.currentTimeMillis() + Math.max(60, expires - 60) * 1000L;
        return token;
    }

    private synchronized void invalidateToken() {
        accessToken = null;
        tokenExpiresAt = 0;
    }

    private String gatewayUrl(String token) {
        if (!settings.gateway().isEmpty()) {
            String configured = settings.gateway();
            if (!configured.startsWith("ws://") && !configured.startsWith("wss://")) {
                throw new IllegalArgumentException("connection.gateway 必须是 ws:// 或 wss:// 地址");
            }
            return configured;
        }
        String base = settings.sandbox() ? SANDBOX_API : PROD_API;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/gateway"))
                    .timeout(Duration.ofSeconds(settings.timeoutSeconds()))
                    .header("Authorization", "QQBot " + token)
                    .header("X-Union-Appid", settings.appId())
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 401 || response.statusCode() == 403) invalidateToken();
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String url = stringField(JsonParser.parseString(response.body()).getAsJsonObject(), "url");
                if (url.startsWith("ws://") || url.startsWith("wss://")) return url;
            }
            logger.warning("获取 QQ 网关地址失败（HTTP " + response.statusCode() + "），使用内置地址");
        } catch (Exception error) {
            logger.warning("获取 QQ 网关地址失败（" + message(error) + "），使用内置地址");
        }
        return settings.sandbox() ? SANDBOX_GATEWAY : PROD_GATEWAY;
    }

    // ---------------- 发送消息 ----------------

    /**
     * 同步发送一条 QQ 消息（调用方是插件自己的发送线程，不占用主线程）。
     *
     * @param msgId  被动回复时的事件 ID；主动消息传空
     * @param msgSeq 同一 msgId 的回复序号，从 1 开始
     */
    public SendResult send(QqTarget target, String content, String msgId, int msgSeq) {
        if (closed) return SendResult.failure("客户端已关闭");
        if (target == null || content == null || content.isBlank()) return SendResult.failure("消息为空");
        try {
            String token = accessToken();
            JsonObject body = new JsonObject();
            body.addProperty("content", content);
            String path;
            if (target.type().isChannel()) {
                path = "/channels/" + encode(target.id()) + "/messages";
                if (msgId != null && !msgId.isBlank()) body.addProperty("msg_id", msgId);
            } else {
                body.addProperty("msg_type", 0);
                path = (target.type() == QqTarget.Type.GROUP
                        ? "/v2/groups/" : "/v2/users/") + encode(target.id()) + "/messages";
                if (msgId != null && !msgId.isBlank()) {
                    body.addProperty("msg_id", msgId);
                    body.addProperty("msg_seq", Math.max(1, msgSeq));
                }
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase() + path))
                    .timeout(Duration.ofSeconds(settings.timeoutSeconds()))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", "QQBot " + token)
                    .header("X-Union-Appid", settings.appId())
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            if (response.statusCode() == 401 || response.statusCode() == 403) invalidateToken();
            long retryAfter = retryAfter(response);
            String detail = ok ? "" : "HTTP " + response.statusCode() + " " + truncate(response.body(), 300);
            if (!ok) warnSendFailure(target, detail);
            return new SendResult(ok, response.statusCode(), detail, retryAfter);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return SendResult.failure("发送被中断");
        } catch (Exception error) {
            String detail = message(error);
            warnSendFailure(target, detail);
            return SendResult.failure(detail);
        }
    }

    /** 发送失败日志限频，避免网络/频率问题每秒钟刷屏。 */
    private void warnSendFailure(QqTarget target, String detail) {
        long now = System.currentTimeMillis();
        if (now - lastSendWarnAt < 30_000L) {
            logger.log(Level.FINE, "QQ 消息发送失败（" + target.key() + "）：" + detail);
            return;
        }
        lastSendWarnAt = now;
        logger.warning("QQ 消息发送失败（" + target.key() + "）：" + detail);
    }

    private String apiBase() {
        return settings.sandbox() ? SANDBOX_API : PROD_API;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static long retryAfter(HttpResponse<?> response) {
        String value = response.headers().firstValue("Retry-After").orElse("");
        if (value.isBlank()) return 0;
        try {
            return Math.max(0, Math.min(60_000, (long) (Double.parseDouble(value.strip()) * 1000)));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    // ---------------- 关闭 ----------------

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        WebSocket old;
        synchronized (stateLock) {
            generation++;
            reconnectGeneration++;
            old = socket;
            socket = null;
            ready = false;
            cancelHeartbeatLocked();
        }
        abort(old);
        reconnecting.set(false);
        scheduler.shutdownNow();
        executor.shutdownNow();
        try {
            http.shutdownNow();
        } catch (RuntimeException ignored) {
            // 已关闭/中断时不再处理
        }
        setStatus("stopped");
    }

    // ---------------- JSON 辅助 ----------------

    private static JsonObject object(JsonObject source, String key) {
        JsonElement value = source == null ? null : source.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String stringField(JsonObject source, String key) {
        JsonElement value = source == null ? null : source.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return "";
        try {
            return value.getAsString().strip();
        } catch (RuntimeException error) {
            return "";
        }
    }

    private static int intField(JsonObject source, String key, int fallback) {
        JsonElement value = source == null ? null : source.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return fallback;
        try {
            return value.getAsInt();
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private static long longField(JsonObject source, String key, long fallback) {
        JsonElement value = source == null ? null : source.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return fallback;
        try {
            return value.getAsLong();
        } catch (RuntimeException error) {
            try {
                return Long.parseLong(value.getAsString().strip());
            } catch (RuntimeException ignored) {
                return fallback;
            }
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        String value = text.replace('\n', ' ').replace('\r', ' ');
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String message(Throwable error) {
        Throwable current = error;
        while ((current instanceof ExecutionException || current instanceof java.util.concurrent.CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}

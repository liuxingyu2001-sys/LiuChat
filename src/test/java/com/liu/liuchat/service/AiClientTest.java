package com.liu.liuchat.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiClientTest {
    private final AtomicReference<String> request = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    private HttpServer serve() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"  你好  \"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        return server;
    }

    private String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private static String role(JsonArray messages, int index) {
        return messages.get(index).getAsJsonObject().get("role").getAsString();
    }

    private static String content(JsonArray messages, int index) {
        return messages.get(index).getAsJsonObject().get("content").getAsString();
    }

    @Test void sendsOpenAiCompatibleRequest() throws Exception {
        HttpServer server = serve();
        try {
            String answer = new AiClient().complete(url(server), "secret", "test-model", "system", "你好?")
                    .get(5, TimeUnit.SECONDS);
            assertEquals("你好", answer);
            assertEquals("Bearer secret", authorization.get());
            var json = JsonParser.parseString(request.get()).getAsJsonObject();
            assertEquals("test-model", json.get("model").getAsString());
            assertEquals("你好?", content(json.getAsJsonArray("messages"), 1));
        } finally {
            server.stop(0);
        }
    }

    @Test void putsHistoryBetweenSystemAndQuestionAndSetsMaxTokens() throws Exception {
        HttpServer server = serve();
        try {
            List<AiClient.Msg> history = List.of(
                    new AiClient.Msg("assistant", "上一轮回答"),
                    new AiClient.Msg("user", "上一轮问题"),
                    new AiClient.Msg("user", "  ")); // 空白历史不发
            new AiClient().complete(url(server), "", "model", "系统提示", history, "本次问题", 5, 512)
                    .get(5, TimeUnit.SECONDS);
            var json = JsonParser.parseString(request.get()).getAsJsonObject();
            var messages = json.getAsJsonArray("messages");
            assertEquals(4, messages.size());
            assertEquals("system", role(messages, 0));
            assertEquals("系统提示", content(messages, 0));
            assertEquals("assistant", role(messages, 1));
            assertEquals("上一轮回答", content(messages, 1));
            assertEquals("user", role(messages, 2));
            assertEquals("上一轮问题", content(messages, 2));
            assertEquals("user", role(messages, 3));
            assertEquals("本次问题", content(messages, 3));
            assertEquals(512, json.get("max_tokens").getAsInt());
        } finally {
            server.stop(0);
        }
    }

    @Test void omitsMaxTokensWhenDisabled() throws Exception {
        HttpServer server = serve();
        try {
            new AiClient().complete(url(server), "", "model", "系统提示", "问题", 5)
                    .get(5, TimeUnit.SECONDS);
            var json = JsonParser.parseString(request.get()).getAsJsonObject();
            assertFalse(json.has("max_tokens"));
            assertEquals(2, json.getAsJsonArray("messages").size());
            assertEquals("问题", content(json.getAsJsonArray("messages"), 1));
        } finally {
            server.stop(0);
        }
    }

    @Test void acceptsFullEndpointWithoutDoublingPath() {
        assertEquals("https://example.org/v1/chat/completions",
                AiClient.endpoint("https://example.org/v1/chat/completions").toString());
        assertEquals("https://example.org/v1/chat/completions",
                AiClient.endpoint("https://example.org/v1/").toString());
    }

    @Test void honorsAssistantTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                Thread.sleep(1500);
                byte[] response = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                try (var out = exchange.getResponseBody()) { out.write(response); }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException ignored) {
                // Client may close the timed-out connection.
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            assertTrue(new AiClient().complete(url, "", "model", "system", "question", 1)
                    .handle((answer, error) -> error != null).get(4, TimeUnit.SECONDS));
            assertEquals("ok", new AiClient().complete(url, "", "model", "system", "question", 4)
                    .get(6, TimeUnit.SECONDS));
        } finally {
            server.stop(0);
        }
    }

    @Test void rejectsBadResponses() {
        assertThrows(IllegalArgumentException.class, () -> AiClient.parseResponse(429, "rate limited"));
        assertThrows(RuntimeException.class, () -> AiClient.parseResponse(200, "{}"));
        assertTrue(new AiClient().complete("file:///tmp/key", "", "m", "p", "q").isCompletedExceptionally());
    }
}

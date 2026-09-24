package com.liu.liuchat.service;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiClientTest {
    @Test void sendsOpenAiCompatibleRequest() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> request = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"  你好  \"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            String answer = new AiClient().complete("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/v1", "secret", "test-model", "system", "你好?")
                    .get(5, TimeUnit.SECONDS);
            assertEquals("你好", answer);
            assertEquals("Bearer secret", authorization.get());
            var json = JsonParser.parseString(request.get()).getAsJsonObject();
            assertEquals("test-model", json.get("model").getAsString());
            assertEquals("你好?", json.getAsJsonArray("messages").get(1)
                    .getAsJsonObject().get("content").getAsString());
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

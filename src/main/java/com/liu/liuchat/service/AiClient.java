package com.liu.liuchat.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Shared OpenAI-compatible chat/completions transport; never blocks the server thread. */
public final class AiClient {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public CompletableFuture<String> complete(String url, String key, String model,
                                               String systemPrompt, String userText) {
        return complete(url, key, model, systemPrompt, userText, 8);
    }

    public CompletableFuture<String> complete(String url, String key, String model,
                                               String systemPrompt, String userText, int timeoutSeconds) {
        try {
            URI uri = endpoint(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
                throw new IllegalArgumentException("AI URL must use HTTP(S)");
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            JsonArray prompts = new JsonArray();
            prompts.add(message("system", systemPrompt));
            prompts.add(message("user", userText));
            body.add("messages", prompts);
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(Math.max(1, Math.min(120, timeoutSeconds))))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
            if (key != null && !key.isBlank()) builder.header("Authorization", "Bearer " + key);
            return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> parseResponse(response.statusCode(), response.body()));
        } catch (RuntimeException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    static URI endpoint(String url) {
        URI uri = URI.create(url.strip());
        String path = uri.getPath();
        if (path == null || path.isEmpty() || path.equals("/") || path.endsWith("/v1")
                || path.endsWith("/v1/")) {
            String base = url.strip().replaceAll("/+$", "");
            return URI.create(base + "/chat/completions");
        }
        return uri;
    }

    private static JsonObject message(String role, String text) {
        JsonObject value = new JsonObject();
        value.addProperty("role", role);
        value.addProperty("content", text);
        return value;
    }

    static String parseResponse(int status, String body) {
        if (status < 200 || status >= 300 || body.length() > 65536)
            throw new IllegalArgumentException("AI returned HTTP " + status + " or oversized response");
        String answer = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("choices")
                .get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString().strip();
        if (answer.isEmpty()) throw new IllegalArgumentException("AI returned empty content");
        return answer;
    }
}

package com.liu.liuchat.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Shared OpenAI-compatible chat/completions transport; never blocks the server thread. */
public final class AiClient {
    /** 一条对话消息；role 只会是 user 或 assistant。 */
    public record Msg(String role, String content) { }

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public CompletableFuture<String> complete(String url, String key, String model,
                                               String systemPrompt, String userText) {
        return complete(url, key, model, systemPrompt, userText, 8);
    }

    public CompletableFuture<String> complete(String url, String key, String model,
                                               String systemPrompt, String userText, int timeoutSeconds) {
        return complete(url, key, model, systemPrompt, List.of(), userText, timeoutSeconds, 0);
    }

    /**
     * 多轮请求：system 永远是第一条且内容保持逐字节不变，历史消息紧跟其后，最后才是本次问题。
     * 这个顺序是刻意的 —— OpenAI / DeepSeek / Kimi 等服务按前缀缓存计价，稳定的 system 前缀
     * 命中缓存后只按约 1/10 价格计费，所以不要往 system 前面插入时间戳、玩家名等动态内容。
     *
     * @param history   之前的问答（不含本次问题），按时间顺序；null/空白项会被跳过
     * @param maxTokens 输出 token 上限，0 = 不传该字段
     */
    public CompletableFuture<String> complete(String url, String key, String model,
                                              String systemPrompt, List<Msg> history, String userText,
                                              int timeoutSeconds, int maxTokens) {
        try {
            URI uri = endpoint(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
                throw new IllegalArgumentException("AI URL must use HTTP(S)");
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            JsonArray prompts = new JsonArray();
            prompts.add(message("system", systemPrompt));
            if (history != null) {
                for (Msg msg : history) {
                    if (msg == null || msg.content() == null || msg.content().isBlank()) continue;
                    prompts.add(message(msg.role(), msg.content()));
                }
            }
            prompts.add(message("user", userText));
            body.add("messages", prompts);
            if (maxTokens > 0) body.addProperty("max_tokens", maxTokens);
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

package com.liu.liuchat.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Formats assistant output for Minecraft chat without Markdown layout. */
public final class AiAnswerFormatter {
    private static final Pattern CODE_FENCE = Pattern.compile("```[^\\n]*\\n?|```");
    private static final Pattern MARKDOWN = Pattern.compile("[*_~#>`]");
    private static final Pattern COMMAND = Pattern.compile("(?<!\\S)(/[-a-zA-Z0-9_:]+(?:\\s+[^\\s]+){0,8})");
    private static final int MAX_LINE = 42;

    private AiAnswerFormatter() { }

    public static List<String> lines(String answer) {
        String source = answer == null ? "" : CODE_FENCE.matcher(answer).replaceAll("");
        source = MARKDOWN.matcher(source).replaceAll("").replace('\r', ' ');
        List<String> result = new ArrayList<>();
        for (String paragraph : source.split("\\n+")) {
            String text = paragraph.strip();
            if (text.isEmpty()) continue;
            var command = COMMAND.matcher(text);
            if (command.find() && command.start() > 0) {
                addWrapped(result, text.substring(0, command.start()).strip(), "");
                addWrapped(result, command.group(1).strip(), "&b");
            } else {
                addWrapped(result, text, "");
            }
        }
        return result.isEmpty() ? List.of("&7（助手没有返回内容）") : List.copyOf(result);
    }

    private static void addWrapped(List<String> out, String text, String prefix) {
        if (text.isEmpty()) return;
        String remaining = text;
        int width = Math.max(1, MAX_LINE - visibleLength(prefix));
        while (remaining.length() > width) {
            int cut = remaining.lastIndexOf(' ', width);
            if (cut <= 0) cut = width;
            out.add(prefix + remaining.substring(0, cut).strip());
            remaining = remaining.substring(cut).strip();
        }
        if (!remaining.isEmpty()) out.add(prefix + remaining);
    }

    private static int visibleLength(String text) {
        return text.replaceAll("&[0-9a-fk-or]", "").length();
    }
}

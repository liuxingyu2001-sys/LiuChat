package com.liu.liuchat.service;

import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/** Single pass: placeholder-looking text inside player supplied values stays literal. */
public final class ChannelFormat {
    private static final Pattern TOKEN = Pattern.compile("\\$\\{(channel_name|channel|server|world|player|nick|message)}");
    private ChannelFormat() { }
    public static String apply(String template, Map<String, String> values) {
        return TOKEN.matcher(template).replaceAll(match -> Matcher.quoteReplacement(
                values.getOrDefault(match.group(1), match.group())));
    }
}

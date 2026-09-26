package com.liu.liuchat.service;

import java.util.List;
import java.util.regex.Pattern;

/** Fast local moderation gate; no network request is made for blocked messages. */
public final class ChatReviewPolicy {
    private static final Pattern CONTACT = Pattern.compile("(?<!\\d)(?:1[3-9]\\d{9}|[1-9]\\d{5,11})(?!\\d)");
    private static final Pattern IPV4 = Pattern.compile("(?<![\\w.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\d.])");
    private static final Pattern DOMAIN = Pattern.compile(
            "(?i)(?<![a-z0-9_.-])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+"
                    + "[a-z]{2,24}(?::[0-9]{1,5})?(?![a-z0-9_.-])");
    private static final int MAX_KEYWORDS = 64;
    private static final int MAX_KEYWORD_LENGTH = 64;
    private static final int MAX_GAP = 2;
    private static final int MAX_WILDCARD_GAP = 8;

    private ChatReviewPolicy() { }

    static Pattern keywordPattern(String keyword) {
        if (keyword == null || keyword.isBlank() || keyword.length() > MAX_KEYWORD_LENGTH) return null;
        String value = keyword.strip();
        StringBuilder regex = new StringBuilder();
        boolean gap = false;
        int literals = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '*') {
                regex.append("[\\s\\S]{0,").append(MAX_WILDCARD_GAP).append('}');
                gap = false;
            } else if (c == '?') {
                regex.append("[\\s\\S]");
                gap = false;
            } else {
                literals++;
                if (gap) regex.append("[\\s\\S]{0,").append(MAX_GAP).append('}');
                regex.append(Pattern.quote(String.valueOf(c)));
                gap = true;
            }
        }
        return literals == 0 ? null : Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    public static boolean blocked(String message, List<String> matchKeywords, List<List<String>> allGroups,
                                  boolean contacts, boolean blockIps, boolean blockDomains) {
        if (message == null) return false;
        if (blockIps && containsIpv4(message) || blockDomains && DOMAIN.matcher(message).find()) return true;
        if (message.length() > 1000 || contacts && CONTACT.matcher(message).find()) return true;
        if (matchesAny(message, matchKeywords)) return true;
        for (int i = 0; i < Math.min(allGroups.size(), MAX_KEYWORDS); i++) {
            if (matchesAll(message, allGroups.get(i))) return true;
        }
        return false;
    }

    private static boolean matchesAny(String message, List<String> keywords) {
        for (int i = 0; i < Math.min(keywords.size(), MAX_KEYWORDS); i++) {
            Pattern pattern = keywordPattern(keywords.get(i));
            if (pattern != null && pattern.matcher(message).find()) return true;
        }
        return false;
    }

    private static boolean matchesAll(String message, List<String> keywords) {
        boolean hasValidKeyword = false;
        for (int i = 0; i < Math.min(keywords.size(), MAX_KEYWORDS); i++) {
            Pattern pattern = keywordPattern(keywords.get(i));
            if (pattern == null) continue;
            hasValidKeyword = true;
            if (!pattern.matcher(message).find()) return false;
        }
        return hasValidKeyword;
    }

    static boolean containsIpv4(String message) {
        var matcher = IPV4.matcher(message);
        while (matcher.find()) {
            String[] octets = matcher.group().split("\\.");
            boolean valid = true;
            for (String octet : octets) {
                if (Integer.parseInt(octet) > 255) { valid = false; break; }
            }
            if (valid) return true;
        }
        return false;
    }
}

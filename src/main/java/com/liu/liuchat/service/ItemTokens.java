package com.liu.liuchat.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 聊天里的物品展示 token：{@code [i]}、{@code [i12]}、{@code [i1-9]}、{@code [盔甲]}、{@code [副手]}。
 *
 * <p>发送、渲染、个人配色保护、重复检测四处共用同一份解析结果——token 一旦从单个字面量变成一族，
 * 漏改任何一处的表现都是 chip 渲染不出来、或者配色把 token 内部染色导致正则失配。
 *
 * <p>展开规则（全部不区分大小写）：
 * <ul>
 *   <li>{@code [i]} = 主手（当前选中格），字面量由 {@code item.format} 配置；</li>
 *   <li>{@code [i1]}..{@code [i9]} = 快捷栏左起第 1..9 格；{@code [i1234]} 是紧凑写法（每格一个数字，
 *       重复数字去重）；{@code [i1-9]} / {@code [i9-1]} 是连续区间（含降序）；</li>
 *   <li>{@code [盔甲]}（{@code [armor]}）= 头、胸、腿、脚；{@code [副手]}（{@code [offhand]}）= 副手；</li>
 *   <li>不识别的写法（{@code [i0]}、{@code [i10]}、{@code [i12-3]}）原样当普通文字，不取消消息。</li>
 * </ul>
 */
public final class ItemTokens {

    /** 展示槽位的种类。 */
    public enum Kind { MAIN_HAND, HOTBAR, ARMOR, OFFHAND }

    /**
     * 一个槽位引用。
     *
     * @param kind  槽位种类
     * @param index HOTBAR 为 1..9（快捷栏左起），ARMOR 为 0..3（头胸腿脚），其余为 0
     */
    public record Ref(Kind kind, int index) { }

    /** 解析出的一段：普通文本 或 一个会展开成多件物品的 token。 */
    public sealed interface Part permits Plain, Token { }

    /** 普通文本；也包含不合法的 token 写法（保持原样）。 */
    public record Plain(String value) implements Part { }

    /**
     * 一个 token。
     *
     * @param raw  原文，拿不到物品数据时原样回显，避免吞掉玩家输入
     * @param refs 该 token 要展开的槽位（已去重）
     */
    public record Token(String raw, List<Ref> refs) implements Part { }

    /** 哪些 token 开启；{@code baseToken} 是 item.format 的字面量。 */
    public record Settings(String baseToken, boolean slots, boolean armor, boolean offhand) { }

    /** 没有任何 token 时用的永不匹配模式（避免空分支匹配空串）。 */
    private static final Pattern NEVER = Pattern.compile("(?!)");

    private ItemTokens() { }

    /** 用配置生成解析器；解析、渲染、配色保护共用同一个实例。 */
    public static Parser parser(Settings settings) {
        return new Parser(pattern(settings), settings);
    }

    /** 生成匹配模式：关键字与槽位分支在前，可配置的字面量在后（前者更具体）。 */
    public static Pattern pattern(Settings settings) {
        if (settings == null) return NEVER;
        List<String> alternatives = new ArrayList<>();
        if (settings.armor()) {
            alternatives.add("\\[盔甲\\]");
            alternatives.add("\\[armor\\]");
        }
        if (settings.offhand()) {
            alternatives.add("\\[副手\\]");
            alternatives.add("\\[offhand\\]");
        }
        if (settings.slots()) {
            alternatives.add("\\[i[1-9]-[1-9]\\]");
            alternatives.add("\\[i[1-9]+\\]");
        }
        String base = settings.baseToken();
        if (base != null && !base.isEmpty()) alternatives.add(Pattern.quote(base));
        if (alternatives.isEmpty()) return NEVER;
        return Pattern.compile(String.join("|", alternatives), Pattern.CASE_INSENSITIVE);
    }

    /** 解析器：持有模式与配置，所有调用点共用。 */
    public record Parser(Pattern pattern, Settings settings) {

        /** 消息里是否存在可展示的 token。 */
        public boolean hasToken(String message) {
            return pattern != null && message != null && !message.isEmpty()
                    && pattern.matcher(message).find();
        }

        /** 从 offset 起正好是一个 token 时返回其结束位置，否则返回 -1（供配色保护跳过 token）。 */
        public int tokenEnd(String message, int offset) {
            if (pattern == null || message == null || offset < 0 || offset >= message.length()) return -1;
            Matcher matcher = pattern.matcher(message);
            matcher.region(offset, message.length());
            return matcher.lookingAt() ? matcher.end() : -1;
        }

        /** 把消息切成文本段与 token 段；没有 token 时整条就是一段普通文本。 */
        public List<Part> parse(String message) {
            if (message == null || message.isEmpty()) return List.of();
            Matcher matcher = pattern.matcher(message);
            List<Part> out = new ArrayList<>();
            int at = 0;
            while (matcher.find()) {
                String raw = matcher.group();
                List<Ref> refs = refs(raw);
                // 归类失败（理论上不可达）→ 不当作 token，原文并入后面的文本段
                if (refs == null) continue;
                if (matcher.start() > at) out.add(new Plain(message.substring(at, matcher.start())));
                out.add(new Token(raw, refs));
                at = matcher.end();
            }
            if (at < message.length()) out.add(new Plain(message.substring(at)));
            return List.copyOf(out);
        }

        /** 把每个 token 换成 replacement（无 token 时原样返回）。 */
        public String replace(String message, String replacement) {
            if (message == null) return null;
            StringBuilder out = new StringBuilder(message.length());
            for (Part part : parse(message)) {
                out.append(part instanceof Token ? replacement : ((Plain) part).value());
            }
            return out.toString();
        }

        /** 所有 token 在原始消息里的 [start, end) 区间，供 @ 提及跳过（染进去会让 token 正则失配）。 */
        public List<int[]> spans(String message) {
            if (pattern == null || message == null || message.isEmpty()) return List.of();
            Matcher matcher = pattern.matcher(message);
            List<int[]> out = new ArrayList<>();
            while (matcher.find()) out.add(new int[]{matcher.start(), matcher.end()});
            return out;
        }

        /** token 归类；无法归类时返回 null（调用方按普通文字处理）。 */
        private List<Ref> refs(String raw) {
            if (raw.equalsIgnoreCase(settings.baseToken())) return List.of(new Ref(Kind.MAIN_HAND, 0));
            if (raw.length() < 3 || raw.charAt(0) != '[' || raw.charAt(raw.length() - 1) != ']') return null;
            String inner = raw.substring(1, raw.length() - 1);
            String lower = inner.toLowerCase(Locale.ROOT);
            if (settings.armor() && (lower.equals("盔甲") || lower.equals("armor"))) return armor();
            if (settings.offhand() && (lower.equals("副手") || lower.equals("offhand"))) {
                return List.of(new Ref(Kind.OFFHAND, 0));
            }
            // 槽位 token 形如 [i12]：去括号后还剩前导 i，再去掉才是槽位串
            if (settings.slots() && inner.length() >= 2
                    && (inner.charAt(0) == 'i' || inner.charAt(0) == 'I')) {
                return hotbar(inner.substring(1));
            }
            return null;
        }

        /** 单数字逐格（去重）或 单数字-单数字 区间（支持降序）。 */
        private static List<Ref> hotbar(String key) {
            Set<Integer> slots = new LinkedHashSet<>();
            int dash = key.indexOf('-');
            if (dash >= 0) {
                if (key.length() != 3 || dash != 1) return null;
                int from = key.charAt(0) - '0';
                int to = key.charAt(2) - '0';
                if (from < 1 || from > 9 || to < 1 || to > 9) return null;
                int step = from <= to ? 1 : -1;
                for (int slot = from; slot != to + step; slot += step) slots.add(slot);
            } else {
                for (int i = 0; i < key.length(); i++) {
                    char digit = key.charAt(i);
                    if (digit < '1' || digit > '9') return null;
                    slots.add(digit - '0');
                }
            }
            if (slots.isEmpty()) return null;
            List<Ref> refs = new ArrayList<>(slots.size());
            for (int slot : slots) refs.add(new Ref(Kind.HOTBAR, slot));
            return List.copyOf(refs);
        }

        /** 盔甲槽固定顺序：头、胸、腿、脚。 */
        private static List<Ref> armor() {
            return List.of(new Ref(Kind.ARMOR, 0), new Ref(Kind.ARMOR, 1),
                    new Ref(Kind.ARMOR, 2), new Ref(Kind.ARMOR, 3));
        }
    }
}

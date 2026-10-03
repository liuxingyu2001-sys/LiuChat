package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemTokensTest {

    private static ItemTokens.Parser parser() {
        return ItemTokens.parser(new ItemTokens.Settings("[i]", true, true, true));
    }

    /** 按书写顺序收集所有 token 的槽位引用。 */
    private static List<ItemTokens.Ref> refs(String message) {
        List<ItemTokens.Ref> out = new ArrayList<>();
        for (ItemTokens.Part part : parser().parse(message)) {
            if (part instanceof ItemTokens.Token token) out.addAll(token.refs());
        }
        return out;
    }

    private static List<String> raws(String message) {
        List<String> out = new ArrayList<>();
        for (ItemTokens.Part part : parser().parse(message)) {
            if (part instanceof ItemTokens.Token token) out.add(token.raw());
        }
        return out;
    }

    @Test void baseTokenIsMainHand() {
        assertEquals(List.of(new ItemTokens.Ref(ItemTokens.Kind.MAIN_HAND, 0)), refs("[i]"));
        assertEquals(List.of(new ItemTokens.Ref(ItemTokens.Kind.MAIN_HAND, 0)), refs("[I]"));
    }

    @Test void hotbarSlotsAreLeftToRightOneBased() {
        assertEquals(List.of(new ItemTokens.Ref(ItemTokens.Kind.HOTBAR, 1)), refs("[i1]"));
        assertEquals(List.of(new ItemTokens.Ref(ItemTokens.Kind.HOTBAR, 9)), refs("[i9]"));
        assertEquals(List.of(
                new ItemTokens.Ref(ItemTokens.Kind.HOTBAR, 1),
                new ItemTokens.Ref(ItemTokens.Kind.HOTBAR, 3)), refs("[i13]"));
    }

    @Test void compactDigitsAndRangesExpandTheSameWay() {
        assertEquals(List.of(
                new ItemTokens.Ref(ItemTokens.Kind.HOTBAR, 1),
                new ItemTokens.Ref(ItemTokens.Kind.HOTBAR, 2),
                new ItemTokens.Ref(ItemTokens.Kind.HOTBAR, 3),
                new ItemTokens.Ref(ItemTokens.Kind.HOTBAR, 4)), refs("[i1234]"));
        assertEquals(refs("[i1234]"), refs("[i1-4]"));
        // 降序区间按书写方向展开（只影响显示先后）
        assertEquals(List.of(
                new ItemTokens.Ref(ItemTokens.Kind.HOTBAR, 4),
                new ItemTokens.Ref(ItemTokens.Kind.HOTBAR, 3),
                new ItemTokens.Ref(ItemTokens.Kind.HOTBAR, 2),
                new ItemTokens.Ref(ItemTokens.Kind.HOTBAR, 1)), refs("[i4-1]"));
    }

    @Test void repeatedDigitsAreDeduplicated() {
        assertEquals(List.of(new ItemTokens.Ref(ItemTokens.Kind.HOTBAR, 1)), refs("[i11]"));
        assertEquals(List.of(
                new ItemTokens.Ref(ItemTokens.Kind.HOTBAR, 9),
                new ItemTokens.Ref(ItemTokens.Kind.HOTBAR, 1)), refs("[i91]"));
    }

    @Test void armorAndOffhandTokensInBothSpellingsAndCase() {
        List<ItemTokens.Ref> armor = refs("[盔甲]");
        assertEquals(4, armor.size());
        for (int i = 0; i < 4; i++) {
            assertEquals(new ItemTokens.Ref(ItemTokens.Kind.ARMOR, i), armor.get(i));
        }
        assertEquals(armor, refs("[armor]"));
        assertEquals(armor, refs("[ARMOR]"));
        assertEquals(List.of(new ItemTokens.Ref(ItemTokens.Kind.OFFHAND, 0)), refs("[副手]"));
        assertEquals(List.of(new ItemTokens.Ref(ItemTokens.Kind.OFFHAND, 0)), refs("[OffHand]"));
    }

    @Test void illegalWritingsStayPlainTextAndMessageIsNotDropped() {
        for (String bad : List.of("[i0]", "[i10]", "[i-]", "[i12-3]", "[i9-1-9]")) {
            assertTrue(parser().parse(bad).getFirst() instanceof ItemTokens.Plain, bad);
            assertFalse(parser().hasToken(bad), bad);
        }
        assertEquals("[i0] 也是文字", parser().replace("[i0] 也是文字", "[X]"));
    }

    @Test void disabledFlagsTurnTokensIntoPlainText() {
        ItemTokens.Parser off = ItemTokens.parser(new ItemTokens.Settings("[i]", false, false, false));
        assertTrue(off.hasToken("[i]"));
        assertFalse(off.hasToken("[i1]"));
        assertFalse(off.hasToken("[盔甲]"));
        assertFalse(off.hasToken("[副手]"));
        assertEquals(List.of(new ItemTokens.Plain("[i1][盔甲]")), off.parse("[i1][盔甲]"));
    }

    @Test void messageIsSplitIntoTextAndTokenSegmentsInWritingOrder() {
        List<ItemTokens.Part> parts = parser().parse("看看[i12]和[盔甲]！");
        assertEquals(5, parts.size());
        assertEquals("看看", ((ItemTokens.Plain) parts.get(0)).value());
        assertEquals(2, ((ItemTokens.Token) parts.get(1)).refs().size());
        assertEquals("和", ((ItemTokens.Plain) parts.get(2)).value());
        assertEquals(4, ((ItemTokens.Token) parts.get(3)).refs().size());
        assertEquals("！", ((ItemTokens.Plain) parts.get(4)).value());
    }

    @Test void messageWithoutTokensIsOnePlainTextSegment() {
        assertEquals(List.of(new ItemTokens.Plain("普通聊天")), parser().parse("普通聊天"));
        assertEquals(List.of(), parser().parse(""));
    }

    @Test void replaceSwapsEveryTokenButKeepsSurroundingText() {
        assertEquals("[X][X]!", parser().replace("[i12][盔甲]!", "[X]"));
    }

    @Test void tokenEndMarksTheWholeSpanForColorProtection() {
        ItemTokens.Parser parser = parser();
        assertEquals(5, parser.tokenEnd("[i12]", 0));
        assertEquals(5, parser.tokenEnd("x[盔甲]", 1));
        assertEquals(-1, parser.tokenEnd("[i0]", 0));
        assertEquals(-1, parser.tokenEnd("[i]", 3));
    }

    @Test void slotsKeepTheirOwnBracketsWhenBaseTokenDiffers() {
        ItemTokens.Parser custom = ItemTokens.parser(new ItemTokens.Settings("(i)", true, true, true));
        assertEquals(List.of(new ItemTokens.Ref(ItemTokens.Kind.MAIN_HAND, 0)),
                flatten(custom.parse("(i)")));
        // 槽位 token 写法固定为方括号，不受 item.format 影响
        assertEquals(List.of(new ItemTokens.Ref(ItemTokens.Kind.HOTBAR, 1)),
                flatten(custom.parse("[i1]")));
    }

    private static List<ItemTokens.Ref> flatten(List<ItemTokens.Part> parts) {
        List<ItemTokens.Ref> out = new ArrayList<>();
        for (ItemTokens.Part part : parts) {
            if (part instanceof ItemTokens.Token token) out.addAll(token.refs());
        }
        return out;
    }
}

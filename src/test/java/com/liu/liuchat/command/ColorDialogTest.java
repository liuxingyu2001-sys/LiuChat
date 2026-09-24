package com.liu.liuchat.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColorDialogTest {
    @Test void formatsRgbAndStylesForExistingChatProfile() {
        var state = new ColorDialog.ColorState(18, 171, 52, true, true);
        assertEquals("&#12AB34&l&n", state.serialize());
        assertEquals(state, ColorDialog.fromStored(state.serialize()));
        assertEquals(state, ColorDialog.fromStored("<#12AB34>&l&n"));
        assertEquals(0x55ff55, ColorDialog.fromStored("&a").r() << 16
                | ColorDialog.fromStored("&a").g() << 8 | ColorDialog.fromStored("&a").b());
        var plain = ColorDialog.fromStored("");
        assertEquals(255, plain.r());
        assertFalse(plain.bold());
        assertFalse(plain.underline());
        assertTrue(state.withRgb(new int[]{255, 85, 85}).bold());
    }

    @Test void gradientSurvivesSaveAndReload() {
        var state = new ColorDialog.ColorState(true, 26, 255, 240, 46, 164, 255, true, false);
        assertEquals("<gradient:#1AFFF0:#2EA4FF>&l", state.serialize());
        assertEquals(state, ColorDialog.fromStored(state.serialize()));
    }
}

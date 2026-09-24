package com.liu.liuchat.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileTextTest {

    @Test void acceptsColorAndStyleCodesOnly() {
        assertTrue(ProfileText.isColorOnly("&a"));
        assertTrue(ProfileText.isColorOnly("&a&l&c"));
        assertTrue(ProfileText.isColorOnly("&#FF0000"));
        assertTrue(ProfileText.isColorOnly("&x&F&F&0&0&0&0"));
        assertTrue(ProfileText.isColorOnly("<#FF0000>"));
        assertTrue(ProfileText.isColorOnly("<gradient:#1AFFF0:#2EA4FF>"));
        assertTrue(ProfileText.isColorOnly("&a <bold>"));
        assertTrue(ProfileText.isColorOnly("&l&#FF0000&n"));
    }

    @Test void rejectsPlainTextAndInteractiveTags() {
        assertFalse(ProfileText.isColorOnly("hello"));
        assertFalse(ProfileText.isColorOnly("&a hello"));
        assertFalse(ProfileText.isColorOnly("<click:run_command:'/op me'>"));
        assertFalse(ProfileText.isColorOnly("<hover:show_text:'x'>"));
        assertFalse(ProfileText.isColorOnly("§c"));
        assertFalse(ProfileText.isColorOnly("&z"));
        assertFalse(ProfileText.isColorOnly("&a&"));
        assertFalse(ProfileText.isColorOnly(""));
        assertFalse(ProfileText.isColorOnly(null));
    }
}

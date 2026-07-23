package com.bettercli.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiLangTest {

    @AfterEach
    void restoreDefault() {
        UiText.setLang(UiLang.ZH);
        System.clearProperty("bettercli.ui.lang");
    }

    @Test
    void parseAcceptsChineseAndEnglishAliases() {
        assertEquals(UiLang.ZH, UiLang.parse("zh"));
        assertEquals(UiLang.ZH, UiLang.parse("中文"));
        assertEquals(UiLang.EN, UiLang.parse("en"));
        assertEquals(UiLang.EN, UiLang.parse("English"));
    }

    @Test
    void defaultUiTextIsChinese() {
        UiText.setLang(UiLang.ZH);
        assertTrue(UiText.tipsTitle().contains("入门"));
        assertEquals("思考中", UiText.thinkingLabel());
        assertEquals("空闲", UiText.phaseLabel("idle"));
    }

    @Test
    void englishUiText() {
        UiText.setLang(UiLang.EN);
        assertEquals("Tips for getting started:", UiText.tipsTitle());
        assertEquals("Thinking", UiText.thinkingLabel());
        assertEquals("idle", UiText.phaseLabel("idle"));
    }

    @Test
    void systemPropertyOverridesConfig() {
        System.setProperty("bettercli.ui.lang", "en");
        assertEquals(UiLang.EN, UiLang.resolve("zh"));
    }
}

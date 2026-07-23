package com.bettercli.prompt;

import com.bettercli.i18n.UiLang;
import com.bettercli.i18n.UiText;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptAssemblerLanguageTest {

    @AfterEach
    void restore() {
        UiText.setLang(UiLang.ZH);
    }

    @Test
    void applyUiLanguageRewritesChinesePolicy() {
        UiText.setLang(UiLang.ZH);
        String out = PromptAssembler.applyUiLanguage("""
                ## Identity

                x

                ## Language

                old policy

                ## Tools

                y
                """);
        assertTrue(out.contains("请用中文回复用户"));
        assertTrue(out.contains("## Tools"));
    }

    @Test
    void applyUiLanguageRewritesEnglishPolicy() {
        UiText.setLang(UiLang.EN);
        String out = PromptAssembler.applyUiLanguage("""
                ## Language

                请用中文回复用户。

                ## Tools

                y
                """);
        assertTrue(out.contains("Reply to the user in English"));
        assertTrue(!out.contains("请用中文回复用户"));
    }
}

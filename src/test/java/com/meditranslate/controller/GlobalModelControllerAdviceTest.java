package com.meditranslate.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.meditranslate.config.MediTranslateProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GlobalModelControllerAdviceTest {

    @Test
    void mapsSupportedLanguagesToDistinctDisplayNames() {
        MediTranslateProperties properties = new MediTranslateProperties();
        properties.setSupportedLanguages(List.of("en", "hi", "mr", "ta", "te"));

        GlobalModelControllerAdvice advice = new GlobalModelControllerAdvice(properties);
        Map<String, String> options = advice.languageOptions();

        assertEquals("English", options.get("en"));
        assertEquals("Hindi", options.get("hi"));
        assertEquals("Marathi", options.get("mr"));
        assertEquals("Tamil", options.get("ta"));
        assertEquals("Telugu", options.get("te"));
    }
}

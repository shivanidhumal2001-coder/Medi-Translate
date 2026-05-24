package com.meditranslate.controller;

import com.meditranslate.config.MediTranslateProperties;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class GlobalModelControllerAdvice {

    private final MediTranslateProperties properties;

    public GlobalModelControllerAdvice(MediTranslateProperties properties) {
        this.properties = properties;
    }

    @ModelAttribute("languageOptions")
    public Map<String, String> languageOptions() {
        Map<String, String> options = new LinkedHashMap<>();
        for (String code : properties.getSupportedLanguages()) {
            options.put(code, switch (code) {
                case "en" -> "English";
                case "hi" -> "Hindi";
                case "mr" -> "Marathi";
                case "ta" -> "Tamil";
                case "te" -> "Telugu";
                default -> code.toUpperCase(Locale.ROOT);
            });
        }
        return options;
    }

    @ModelAttribute("appName")
    public String appName() {
        return "MediTranslate";
    }
}

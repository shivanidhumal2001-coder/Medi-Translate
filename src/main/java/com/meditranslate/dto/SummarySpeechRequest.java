package com.meditranslate.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class SummarySpeechRequest {

    @NotBlank(message = "Summary text is required")
    @Size(max = 12000, message = "Summary text is too long for audio generation")
    private String text;

    @Size(max = 12, message = "Language code is too long")
    private String language;

    @Size(max = 40, message = "Voice name is too long")
    private String voice;

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getVoice() {
        return voice;
    }

    public void setVoice(String voice) {
        this.voice = voice;
    }
}

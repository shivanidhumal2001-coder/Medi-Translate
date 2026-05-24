package com.meditranslate.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "medi.translate")
public class MediTranslateProperties {

    private String storagePath = "uploads";
    private List<String> supportedLanguages = new ArrayList<>(List.of("en", "hi", "mr", "ta", "te"));
    private final Claude claude = new Claude();
    private final Gemini gemini = new Gemini();
    private final Ocr ocr = new Ocr();

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public List<String> getSupportedLanguages() {
        return supportedLanguages;
    }

    public void setSupportedLanguages(List<String> supportedLanguages) {
        this.supportedLanguages = supportedLanguages;
    }

    public Claude getClaude() {
        return claude;
    }

    public Gemini getGemini() {
        return gemini;
    }

    public Ocr getOcr() {
        return ocr;
    }

    public static class Claude {
        private boolean enabled;
        private String apiKey;
        private String model = "claude-3-5-sonnet-latest";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    public static class Gemini {
        private boolean enabled;
        private String apiKey;
        private String model = "gemini-2.5-flash";
        private String fallbackModel = "gemini-2.5-flash-lite";
        private String ttsModel = "gemini-3.1-flash-tts-preview";
        private String ttsFallbackModel = "gemini-2.5-flash-preview-tts";
        private String baseUrl = "https://generativelanguage.googleapis.com/v1beta/models";
        private int maxAttemptsPerModel = 2;
        private long retryDelayMs = 1200;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getTtsModel() {
            return ttsModel;
        }

        public void setTtsModel(String ttsModel) {
            this.ttsModel = ttsModel;
        }

        public String getTtsFallbackModel() {
            return ttsFallbackModel;
        }

        public void setTtsFallbackModel(String ttsFallbackModel) {
            this.ttsFallbackModel = ttsFallbackModel;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getFallbackModel() {
            return fallbackModel;
        }

        public void setFallbackModel(String fallbackModel) {
            this.fallbackModel = fallbackModel;
        }

        public int getMaxAttemptsPerModel() {
            return maxAttemptsPerModel;
        }

        public void setMaxAttemptsPerModel(int maxAttemptsPerModel) {
            this.maxAttemptsPerModel = maxAttemptsPerModel;
        }

        public long getRetryDelayMs() {
            return retryDelayMs;
        }

        public void setRetryDelayMs(long retryDelayMs) {
            this.retryDelayMs = retryDelayMs;
        }
    }

    public static class Ocr {
        private String tessdataPath = "/usr/share/tesseract-ocr/5/tessdata";

        public String getTessdataPath() {
            return tessdataPath;
        }

        public void setTessdataPath(String tessdataPath) {
            this.tessdataPath = tessdataPath;
        }
    }
}

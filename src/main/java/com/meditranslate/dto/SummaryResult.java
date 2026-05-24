package com.meditranslate.dto;

import java.util.LinkedHashMap;
import java.util.Map;

public class SummaryResult {

    private String mainSummary;
    private String keyHighlights;
    private String disclaimer;
    private Map<String, String> translatedSummaries = new LinkedHashMap<>();
    private Map<String, String> aiInterpretationsByParameter = new LinkedHashMap<>();

    public String getMainSummary() {
        return mainSummary;
    }

    public void setMainSummary(String mainSummary) {
        this.mainSummary = mainSummary;
    }

    public String getKeyHighlights() {
        return keyHighlights;
    }

    public void setKeyHighlights(String keyHighlights) {
        this.keyHighlights = keyHighlights;
    }

    public String getDisclaimer() {
        return disclaimer;
    }

    public void setDisclaimer(String disclaimer) {
        this.disclaimer = disclaimer;
    }

    public Map<String, String> getTranslatedSummaries() {
        return translatedSummaries;
    }

    public void setTranslatedSummaries(Map<String, String> translatedSummaries) {
        this.translatedSummaries = translatedSummaries;
    }

    public Map<String, String> getAiInterpretationsByParameter() {
        return aiInterpretationsByParameter;
    }

    public void setAiInterpretationsByParameter(Map<String, String> aiInterpretationsByParameter) {
        this.aiInterpretationsByParameter = aiInterpretationsByParameter;
    }
}

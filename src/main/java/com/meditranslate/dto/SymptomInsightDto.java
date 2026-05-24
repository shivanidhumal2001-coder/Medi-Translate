package com.meditranslate.dto;

import java.util.List;

public class SymptomInsightDto {

    private final String symptom;
    private final List<String> relatedFindings;
    private final String guidance;

    public SymptomInsightDto(String symptom, List<String> relatedFindings, String guidance) {
        this.symptom = symptom;
        this.relatedFindings = relatedFindings;
        this.guidance = guidance;
    }

    public String getSymptom() {
        return symptom;
    }

    public List<String> getRelatedFindings() {
        return relatedFindings;
    }

    public String getGuidance() {
        return guidance;
    }
}

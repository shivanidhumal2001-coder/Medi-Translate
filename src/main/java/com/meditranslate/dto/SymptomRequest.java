package com.meditranslate.dto;

import jakarta.validation.constraints.NotBlank;

public class SymptomRequest {

    @NotBlank(message = "Enter at least one symptom")
    private String symptoms;

    public String getSymptoms() {
        return symptoms;
    }

    public void setSymptoms(String symptoms) {
        this.symptoms = symptoms;
    }
}

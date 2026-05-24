package com.meditranslate.dto;

import jakarta.validation.constraints.NotBlank;

public class ReminderRequest {

    @NotBlank(message = "Enter prescription instructions or paste the medicine section")
    private String prescriptionText;

    public String getPrescriptionText() {
        return prescriptionText;
    }

    public void setPrescriptionText(String prescriptionText) {
        this.prescriptionText = prescriptionText;
    }
}

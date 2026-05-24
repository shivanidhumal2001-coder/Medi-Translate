package com.meditranslate.dto;

public class ReminderRowDto {

    private final String medicineName;
    private final String dosage;
    private final String scheduleText;
    private final String mealInstruction;
    private final String durationText;
    private final String notes;

    public ReminderRowDto(String medicineName, String dosage, String scheduleText, String mealInstruction,
                          String durationText, String notes) {
        this.medicineName = medicineName;
        this.dosage = dosage;
        this.scheduleText = scheduleText;
        this.mealInstruction = mealInstruction;
        this.durationText = durationText;
        this.notes = notes;
    }

    public String getMedicineName() {
        return medicineName;
    }

    public String getDosage() {
        return dosage;
    }

    public String getScheduleText() {
        return scheduleText;
    }

    public String getMealInstruction() {
        return mealInstruction;
    }

    public String getDurationText() {
        return durationText;
    }

    public String getNotes() {
        return notes;
    }
}

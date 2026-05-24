package com.meditranslate.service;

import com.meditranslate.dto.ReminderRowDto;
import com.meditranslate.entity.ReportAnalysis;
import java.util.List;

public interface MedicationReminderService {
    List<ReminderRowDto> generateReminders(ReportAnalysis report, String prescriptionText);
}

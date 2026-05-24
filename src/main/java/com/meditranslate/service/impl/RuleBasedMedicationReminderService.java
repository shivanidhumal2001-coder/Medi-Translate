package com.meditranslate.service.impl;

import com.meditranslate.dto.ReminderRowDto;
import com.meditranslate.entity.MedicationReminder;
import com.meditranslate.entity.ReportAnalysis;
import com.meditranslate.repository.ReportAnalysisRepository;
import com.meditranslate.service.MedicationReminderService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RuleBasedMedicationReminderService implements MedicationReminderService {

    private static final Pattern DOSAGE_PATTERN = Pattern.compile("(\\d+\\s*(?:mg|ml|mcg))", Pattern.CASE_INSENSITIVE);

    private final ReportAnalysisRepository reportAnalysisRepository;

    public RuleBasedMedicationReminderService(ReportAnalysisRepository reportAnalysisRepository) {
        this.reportAnalysisRepository = reportAnalysisRepository;
    }

    @Override
    @Transactional
    public List<ReminderRowDto> generateReminders(ReportAnalysis report, String prescriptionText) {
        ReportAnalysis managedReport = reportAnalysisRepository.findDetailedById(report.getId())
                .orElseThrow(() -> new IllegalArgumentException("Report not found"));
        managedReport.getReminders().clear();

        List<ReminderRowDto> reminders = new ArrayList<>();
        for (String rawLine : prescriptionText.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            String medicineName = extractMedicineName(line);
            if (medicineName.isBlank()) {
                continue;
            }

            String dosage = extractPattern(line, DOSAGE_PATTERN);
            String schedule = detectSchedule(line);
            String mealInstruction = detectMealInstruction(line);
            String duration = detectDuration(line);

            MedicationReminder reminder = new MedicationReminder();
            reminder.setMedicineName(medicineName);
            reminder.setDosage(dosage);
            reminder.setScheduleText(schedule);
            reminder.setMealInstruction(mealInstruction);
            reminder.setDurationText(duration);
            reminder.setNotes("Generated from prescription text");
            managedReport.addReminder(reminder);

            reminders.add(new ReminderRowDto(medicineName, dosage, schedule, mealInstruction, duration, reminder.getNotes()));
        }

        if (reminders.isEmpty()) {
            reminders.add(new ReminderRowDto(
                    "Manual review needed",
                    "-",
                    "Prescription text did not match the current parser",
                    "Check with pharmacist or doctor",
                    "-",
                    "Try pasting one medicine per line with dosage and frequency"
            ));
        } else {
            reportAnalysisRepository.save(managedReport);
        }

        return reminders;
    }

    private String extractMedicineName(String line) {
        String cleaned = line.replaceAll("(?i)^(tab|tablet|cap|capsule|syrup|inj|injection)\\.?\\s*", "").trim();
        cleaned = cleaned.replaceAll("\\s+\\d+\\s*(mg|ml|mcg).*", "").trim();
        if (cleaned.length() > 60) {
            cleaned = cleaned.substring(0, 60).trim();
        }
        return cleaned;
    }

    private String detectSchedule(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("tid") || lower.contains("three times")) {
            return "Morning, afternoon, and night";
        }
        if (lower.contains("bd") || lower.contains("bid") || lower.contains("twice")) {
            return "Morning and night";
        }
        if (lower.contains("hs") || lower.contains("bedtime")) {
            return "At bedtime";
        }
        if (lower.contains("sos") || lower.contains("as needed")) {
            return "Only when needed";
        }
        if (lower.contains("od") || lower.contains("once daily") || lower.contains("daily")) {
            return "Once daily";
        }
        return "Review timing manually";
    }

    private String detectMealInstruction(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("after food") || lower.contains("after meal")) {
            return "After food";
        }
        if (lower.contains("before food") || lower.contains("before meal")) {
            return "Before food";
        }
        return "As advised";
    }

    private String detectDuration(String line) {
        Matcher matcher = Pattern.compile("(for\\s+\\d+\\s+days|x\\s*\\d+\\s*days)", Pattern.CASE_INSENSITIVE).matcher(line);
        if (matcher.find()) {
            return matcher.group();
        }
        return "Not specified";
    }

    private String extractPattern(String value, Pattern pattern) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1) : "-";
    }
}

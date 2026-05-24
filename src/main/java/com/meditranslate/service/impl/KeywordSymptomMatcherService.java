package com.meditranslate.service.impl;

import com.meditranslate.dto.SymptomInsightDto;
import com.meditranslate.entity.ReportAnalysis;
import com.meditranslate.entity.ReportFinding;
import com.meditranslate.service.SymptomMatcherService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class KeywordSymptomMatcherService implements SymptomMatcherService {

    private static final Map<String, Set<String>> SYMPTOM_LINKS = new LinkedHashMap<>();

    static {
        SYMPTOM_LINKS.put("fatigue", Set.of("hemoglobin", "thyroid", "tsh", "vitamin d"));
        SYMPTOM_LINKS.put("weakness", Set.of("hemoglobin", "vitamin d", "glucose"));
        SYMPTOM_LINKS.put("fever", Set.of("wbc"));
        SYMPTOM_LINKS.put("thirst", Set.of("glucose", "hba1c"));
        SYMPTOM_LINKS.put("weight gain", Set.of("tsh"));
        SYMPTOM_LINKS.put("palpitations", Set.of("hemoglobin", "tsh"));
        SYMPTOM_LINKS.put("bleeding", Set.of("platelet"));
    }

    @Override
    public List<SymptomInsightDto> matchSymptoms(ReportAnalysis report, String symptomsInput) {
        List<SymptomInsightDto> insights = new ArrayList<>();
        String[] symptoms = symptomsInput.split("[,\n]");

        for (String symptomRaw : symptoms) {
            String symptom = symptomRaw.trim();
            if (symptom.isEmpty()) {
                continue;
            }

            String normalizedSymptom = symptom.toLowerCase(Locale.ROOT);
            Set<String> linkedKeywords = SYMPTOM_LINKS.getOrDefault(normalizedSymptom, Set.of());
            List<String> related = report.getFindings().stream()
                    .filter(ReportFinding::isAbnormal)
                    .filter(finding -> linkedKeywords.stream().anyMatch(keyword ->
                            finding.getParameterName().toLowerCase(Locale.ROOT).contains(keyword)))
                    .map(finding -> finding.getParameterName() + " -> " + finding.getSystemInterpretation())
                    .toList();

            String guidance = related.isEmpty()
                    ? "No strong direct lab link was found in the parsed report. Symptoms should still be discussed with a doctor."
                    : "These abnormal values may be worth discussing first when talking about " + symptom + ".";

            insights.add(new SymptomInsightDto(symptom, related, guidance));
        }

        return insights;
    }
}

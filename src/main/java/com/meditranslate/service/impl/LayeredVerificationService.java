package com.meditranslate.service.impl;

import com.meditranslate.dto.ParsedFindingCandidate;
import com.meditranslate.dto.SummaryResult;
import com.meditranslate.dto.VerificationResult;
import com.meditranslate.entity.FindingStatus;
import com.meditranslate.entity.ReferenceRange;
import com.meditranslate.entity.ReportFinding;
import com.meditranslate.entity.VerificationLayer;
import com.meditranslate.repository.ReferenceRangeRepository;
import com.meditranslate.service.VerificationService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class LayeredVerificationService implements VerificationService {

    private final ReferenceRangeRepository referenceRangeRepository;

    public LayeredVerificationService(ReferenceRangeRepository referenceRangeRepository) {
        this.referenceRangeRepository = referenceRangeRepository;
    }

    @Override
    public VerificationResult verify(String extractedText, List<ParsedFindingCandidate> parsedFindings, SummaryResult summaryResult) {
        List<ReportFinding> findings = new ArrayList<>();
        Map<String, String> aiInterpretations = summaryResult.getAiInterpretationsByParameter();

        for (ParsedFindingCandidate candidate : parsedFindings) {
            findings.add(buildFinding(candidate, aiInterpretations));
        }

        long matchedCount = findings.stream().filter(ReportFinding::isMatched).count();
        double trustScore = findings.isEmpty()
                ? 70.0
                : BigDecimal.valueOf((matchedCount * 100.0) / findings.size())
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue();

        return new VerificationResult(findings, trustScore);
    }

    private ReportFinding buildFinding(ParsedFindingCandidate candidate, Map<String, String> aiInterpretations) {
        ReportFinding finding = new ReportFinding();
        finding.setParameterName(candidate.getParameterName());
        finding.setRawLine(candidate.getRawLine());
        finding.setPatientValue(candidate.getPatientValue());
        finding.setUnit(candidate.getUnit());

        VerificationLayer layer;
        BigDecimal low = null;
        BigDecimal high = null;
        String source;

        if (candidate.hasPrintedRange()) {
            layer = VerificationLayer.LAB_RANGE;
            low = candidate.getPrintedRangeLow();
            high = candidate.getPrintedRangeHigh();
            source = "Lab printed range";
            finding.setNormalRangeText(candidate.getPrintedRangeText());
        } else {
            Optional<ReferenceRange> matchedRange = resolveReferenceRange(candidate.getParameterName());
            if (matchedRange.isPresent() && candidate.hasNumericValue()) {
                ReferenceRange range = matchedRange.get();
                layer = VerificationLayer.GENERAL_RANGE;
                low = range.getLowValue();
                high = range.getHighValue();
                source = range.getSourceName();
                finding.setNormalRangeText(range.getLowValue() + " - " + range.getHighValue());
            } else {
                layer = VerificationLayer.DESCRIPTIVE_RULE;
                source = "Descriptive rule engine";
                finding.setNormalRangeText("Text-based verification");
            }
        }

        finding.setRangeLow(low);
        finding.setRangeHigh(high);
        finding.setVerificationLayer(layer);
        finding.setEvidenceSource(source);

        FindingStatus status = resolveStatus(candidate, low, high);
        finding.setStatus(status);
        finding.setAbnormal(isAbnormal(status));

        String systemInterpretation = toSystemInterpretation(status, source);
        finding.setSystemInterpretation(systemInterpretation);

        String aiInterpretation = aiInterpretations.getOrDefault(normalizeKey(candidate.getParameterName()), "needs review");
        finding.setAiInterpretation(aiInterpretation);
        finding.setMatched(matches(aiInterpretation, status));

        return finding;
    }

    private Optional<ReferenceRange> resolveReferenceRange(String parameterName) {
        Optional<ReferenceRange> directMatch = referenceRangeRepository.findFirstByAnalyteNameIgnoreCase(parameterName);
        if (directMatch.isPresent()) {
            return directMatch;
        }

        String alias = aliasFor(parameterName);
        Optional<ReferenceRange> aliasMatch = referenceRangeRepository.findFirstByAnalyteNameIgnoreCase(alias);
        if (aliasMatch.isPresent()) {
            return aliasMatch;
        }

        return referenceRangeRepository.findByAnalyteNameContainingIgnoreCase(alias).stream().findFirst();
    }

    private FindingStatus resolveStatus(ParsedFindingCandidate candidate, BigDecimal low, BigDecimal high) {
        if (candidate.hasNumericValue() && low != null && high != null) {
            BigDecimal value = candidate.getNumericValue();
            if (value.compareTo(low) < 0) {
                return FindingStatus.LOW;
            }
            if (value.compareTo(high) > 0) {
                return FindingStatus.HIGH;
            }
            return FindingStatus.NORMAL;
        }

        String descriptive = candidate.getDescriptiveValue();
        if (descriptive == null) {
            return FindingStatus.UNKNOWN;
        }

        String value = descriptive.toLowerCase(Locale.ROOT);
        if (value.contains("negative") || value.contains("non-reactive")) {
            return FindingStatus.NEGATIVE;
        }
        if (value.contains("positive") || value.contains("reactive")) {
            return FindingStatus.POSITIVE;
        }
        if (value.contains("normal")) {
            return FindingStatus.NORMAL;
        }
        if (value.contains("mild") || value.contains("moderate") || value.contains("severe") || value.contains("abnormal")) {
            return FindingStatus.ABNORMAL;
        }
        return FindingStatus.UNKNOWN;
    }

    private boolean matches(String aiInterpretation, FindingStatus status) {
        String normalizedAi = aiInterpretation.toLowerCase(Locale.ROOT);
        return switch (status) {
            case LOW -> normalizedAi.contains("lower") || normalizedAi.contains("low");
            case HIGH -> normalizedAi.contains("higher") || normalizedAi.contains("high");
            case NORMAL, NEGATIVE -> normalizedAi.contains("within") || normalizedAi.contains("normal") || normalizedAi.contains("negative");
            case POSITIVE -> normalizedAi.contains("positive") || normalizedAi.contains("reactive");
            case ABNORMAL -> normalizedAi.contains("abnormal") || normalizedAi.contains("attention");
            default -> normalizedAi.contains("review");
        };
    }

    private boolean isAbnormal(FindingStatus status) {
        return status == FindingStatus.LOW
                || status == FindingStatus.HIGH
                || status == FindingStatus.POSITIVE
                || status == FindingStatus.ABNORMAL;
    }

    private String toSystemInterpretation(FindingStatus status, String source) {
        return switch (status) {
            case LOW -> "Below the verified range (" + source + ")";
            case HIGH -> "Above the verified range (" + source + ")";
            case NORMAL -> "Within the verified range (" + source + ")";
            case POSITIVE -> "Positive / reactive on the report";
            case NEGATIVE -> "Negative / non-reactive on the report";
            case ABNORMAL -> "Descriptive abnormal finding detected";
            default -> "Unable to verify confidently";
        };
    }

    private String aliasFor(String parameterName) {
        String normalized = parameterName.toLowerCase(Locale.ROOT);
        if (normalized.contains("hb") || normalized.contains("haemoglobin") || normalized.contains("hemoglobin")) {
            return "Hemoglobin";
        }
        if (normalized.contains("wbc") || normalized.contains("white blood")) {
            return "WBC";
        }
        if (normalized.contains("platelet")) {
            return "Platelet Count";
        }
        if (normalized.contains("rbc")) {
            return "RBC";
        }
        if (normalized.contains("glucose") || normalized.contains("sugar") || normalized.contains("fasting")) {
            return "Fasting Glucose";
        }
        if (normalized.contains("hba1c")) {
            return "HbA1c";
        }
        if (normalized.contains("creatinine")) {
            return "Creatinine";
        }
        if (normalized.contains("cholesterol")) {
            return "Total Cholesterol";
        }
        if (normalized.contains("tsh")) {
            return "TSH";
        }
        if (normalized.contains("vitamin d")) {
            return "Vitamin D";
        }
        return parameterName;
    }

    private String normalizeKey(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}

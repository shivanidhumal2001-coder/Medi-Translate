package com.meditranslate.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meditranslate.config.MediTranslateProperties;
import com.meditranslate.dto.ParsedFindingCandidate;
import com.meditranslate.dto.SummaryResult;
import com.meditranslate.service.ClinicalSummaryService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Service
public class HybridClinicalSummaryService implements ClinicalSummaryService {

    private static final List<String> MEDICAL_HINT_KEYWORDS = List.of(
            "test", "result", "reference", "range", "unit", "sample", "tsh", "thyroid",
            "glucose", "hemoglobin", "platelet", "cholesterol", "creatinine", "serum", "method"
    );

    private final MediTranslateProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient = RestClient.builder()
            .baseUrl("https://api.anthropic.com/v1")
            .build();

    public HybridClinicalSummaryService(MediTranslateProperties properties) {
        this.properties = properties;
    }

    @Override
    public SummaryResult summarize(String extractedText, List<ParsedFindingCandidate> findings, String targetLanguage) {
        String sanitizedLanguage = StringUtils.hasText(targetLanguage) ? targetLanguage : "en";
        SummaryResult fallback = buildFallbackSummary(extractedText, findings, sanitizedLanguage);

        if (!shouldUseClaude()) {
            return fallback;
        }

        try {
            String aiSummary = requestClaudeSummary(extractedText, findings, sanitizedLanguage);
            if (StringUtils.hasText(aiSummary)) {
                fallback.setMainSummary(aiSummary.trim());
                fallback.getTranslatedSummaries().put(sanitizedLanguage, aiSummary.trim());
            }
        } catch (Exception ignored) {
            // Fall back to a local explanation when Claude is unavailable.
        }
        return fallback;
    }

    private boolean shouldUseClaude() {
        return StringUtils.hasText(properties.getClaude().getApiKey())
                && (properties.getClaude().isEnabled() || StringUtils.hasText(System.getenv("ANTHROPIC_API_KEY")));
    }

    private String requestClaudeSummary(String extractedText, List<ParsedFindingCandidate> findings, String targetLanguage)
            throws Exception {
        String findingContext = findings.stream()
                .limit(12)
                .map(this::toSimpleFindingLine)
                .collect(Collectors.joining("\n"));

        Map<String, Object> payload = Map.of(
                "model", properties.getClaude().getModel(),
                "max_tokens", 650,
                "system", "You simplify medical reports for patients. Use plain language, mention urgency carefully, and end with a brief disclaimer.",
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(Map.of(
                                "type", "text",
                                "text", """
                                        Explain this medical report in %s. Keep it short, simple, and safe for a patient.
                                        Mention abnormal findings first, then next-step guidance. Do not diagnose.

                                        Structured findings:
                                        %s

                                        Raw report text:
                                        %s
                                        """.formatted(languageName(targetLanguage), findingContext, extractedText.length() > 7000 ? extractedText.substring(0, 7000) : extractedText)
                        ))
                ))
        );

        String response = restClient.post()
                .uri("/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-api-key", properties.getClaude().getApiKey())
                .header("anthropic-version", "2023-06-01")
                .body(payload)
                .retrieve()
                .body(String.class);

        JsonNode root = objectMapper.readTree(response);
        JsonNode content = root.path("content");
        if (!content.isArray() || content.isEmpty()) {
            return "";
        }
        return content.get(0).path("text").asText("");
    }

    private SummaryResult buildFallbackSummary(String extractedText, List<ParsedFindingCandidate> findings, String targetLanguage) {
        SummaryResult result = new SummaryResult();

        if (isLowConfidenceExtraction(extractedText, findings)) {
            result.setMainSummary(buildLowConfidenceSummary(targetLanguage));
            result.setKeyHighlights(buildLowConfidenceHighlights());
            result.setDisclaimer("This explanation is for understanding your report more easily. Please confirm treatment decisions with a qualified doctor.");
            result.setAiInterpretationsByParameter(Map.of());

            Map<String, String> translations = new LinkedHashMap<>();
            for (String language : properties.getSupportedLanguages()) {
                translations.put(language, buildLowConfidenceSummary(language));
            }
            if (!translations.containsKey(targetLanguage)) {
                translations.put(targetLanguage, result.getMainSummary());
            }
            result.setTranslatedSummaries(translations);
            return result;
        }

        List<String> abnormalLines = new ArrayList<>();
        List<String> normalLines = new ArrayList<>();
        Map<String, String> interpretations = new LinkedHashMap<>();

        for (ParsedFindingCandidate finding : findings) {
            String interpretation = interpret(finding);
            interpretations.put(normalizeKey(finding.getParameterName()), interpretation);
            String sentence = toPatientSentence(finding, interpretation);
            if (interpretation.contains("higher") || interpretation.contains("lower") || interpretation.contains("positive")
                    || interpretation.contains("abnormal") || interpretation.contains("needs review")) {
                abnormalLines.add(sentence);
            } else {
                normalLines.add(sentence);
            }
        }

        String englishSummary = buildEnglishSummary(extractedText, abnormalLines, normalLines);
        result.setMainSummary(buildLocalizedSummary(targetLanguage, abnormalLines, normalLines, extractedText, englishSummary));
        result.setKeyHighlights(buildHighlights(abnormalLines, normalLines));
        result.setDisclaimer("This explanation is for understanding your report more easily. Please confirm treatment decisions with a qualified doctor.");
        result.setAiInterpretationsByParameter(interpretations);

        Map<String, String> translations = new LinkedHashMap<>();
        for (String language : properties.getSupportedLanguages()) {
            translations.put(language, buildLocalizedSummary(language, abnormalLines, normalLines, extractedText, englishSummary));
        }
        if (!translations.containsKey(targetLanguage)) {
            translations.put(targetLanguage, result.getMainSummary());
        }
        result.setTranslatedSummaries(translations);
        return result;
    }

    private boolean isLowConfidenceExtraction(String extractedText, List<ParsedFindingCandidate> findings) {
        if (!StringUtils.hasText(extractedText)) {
            return true;
        }

        String normalizedText = extractedText.toLowerCase(Locale.ROOT);
        long reliableFindings = findings.stream().filter(this::isReliableFinding).count();
        long medicalKeywordHits = MEDICAL_HINT_KEYWORDS.stream()
                .filter(normalizedText::contains)
                .count();
        long noisyLines = extractedText.lines()
                .map(String::trim)
                .filter(line -> line.length() >= 6)
                .filter(this::isMostlyNoise)
                .count();

        if (reliableFindings == 0 && medicalKeywordHits < 2) {
            return true;
        }

        return findings.size() > 8 && reliableFindings <= 1 && noisyLines >= 5;
    }

    private String buildEnglishSummary(String extractedText, List<String> abnormalLines, List<String> normalLines) {
        if (abnormalLines.isEmpty() && normalLines.isEmpty()) {
            return """
                    We could read some report text, but not enough clear test values were found to explain it safely in simple words.
                    Please upload a clearer photo or PDF, or paste the report text directly for a better explanation.
                    """.trim();
        }

        if (abnormalLines.isEmpty() && normalLines.size() == 1) {
            return """
                    This result looks normal.
                    %s
                    In simple words: this value is inside the lab's normal range, so it does not show an obvious problem by itself. Your doctor may still interpret it together with your symptoms, history, or other tests.
                    """.formatted(normalLines.get(0)).trim();
        }

        StringBuilder summary = new StringBuilder();
        if (!abnormalLines.isEmpty()) {
            summary.append("Some report values need attention.\n");
            summary.append("Most important points:\n");
            abnormalLines.stream().limit(4).forEach(line -> summary.append("- ").append(line).append("\n"));
        } else {
            summary.append("The visible test values look normal overall.\n");
        }

        if (!normalLines.isEmpty()) {
            summary.append("Results we could read clearly:\n");
            normalLines.stream().limit(3).forEach(line -> summary.append("- ").append(line).append("\n"));
        }

        if (!abnormalLines.isEmpty()) {
            summary.append("In simple words: one or more results are outside the normal range shown on the report, so it is worth discussing them with a doctor.");
        } else {
            summary.append("In simple words: the values we could read are within the normal ranges shown on the report. That usually means there is no obvious problem in these results, but a doctor should still review them with your symptoms and history.");
        }
        if (StringUtils.hasText(extractedText) && extractedText.toLowerCase(Locale.ROOT).contains("prescription")) {
            summary.append("\nPrescription-style text was also detected, so you can use the reminder section to turn it into a medicine schedule.");
        }
        return summary.toString().trim();
    }

    private String buildLocalizedSummary(String language, List<String> abnormalLines, List<String> normalLines,
                                         String extractedText, String englishSummary) {
        if ("en".equalsIgnoreCase(language)) {
            return englishSummary;
        }

        int abnormalCount = abnormalLines.size();
        int normalCount = normalLines.size();

        return switch (language.toLowerCase(Locale.ROOT)) {
            case "hi" -> """
                    मुख्य बात: %d report finding(s) extra ध्यान मांगते हैं.
                    असामान्य या review वाले points:
                    %s
                    सामान्य दिखने वाले points:
                    %s
                    सरल अर्थ: यह summary समझने में मदद करती है, लेकिन final medical advice के लिए doctor से confirm करना जरूरी है.
                    """.formatted(
                    abnormalCount,
                    listForLanguage(abnormalLines, "कोई major abnormal finding नहीं मिली."),
                    listForLanguage(normalLines, "कोई strong normal marker parse नहीं हुआ.")
            ).trim();
            case "ta" -> """
                    முக்கிய குறிப்பு: %d finding(s) கூடுதல் கவனம் தேவைப்படுகின்றன.
                    கவனிக்க வேண்டியவை:
                    %s
                    நிலையானவை:
                    %s
                    எளிய விளக்கம்: இந்த summary report-ஐ புரிந்துகொள்ள உதவும். இறுதி மருத்துவ ஆலோசனைக்கு மருத்துவரை அணுகவும்.
                    """.formatted(
                    abnormalCount,
                    listForLanguage(abnormalLines, "பெரிய abnormal finding கண்டுபிடிக்கப்படவில்லை."),
                    listForLanguage(normalLines, "வெளிப்படையான normal marker parse ஆகவில்லை.")
            ).trim();
            case "te" -> """
                    ముఖ్య విషయం: %d finding(s) కు అదనపు శ్రద్ధ అవసరం.
                    గమనించాల్సిన పాయింట్లు:
                    %s
                    స్థిరంగా కనిపించినవి:
                    %s
                    సులభమైన అర్థం: ఈ summary report ను అర్థం చేసుకోవడంలో సహాయపడుతుంది. చివరి వైద్య నిర్ణయానికి doctor ను సంప్రదించండి.
                    """.formatted(
                    abnormalCount,
                    listForLanguage(abnormalLines, "పెద్ద abnormal finding కనిపించలేదు."),
                    listForLanguage(normalLines, "స్పష్టమైన normal marker parse కాలేదు.")
            ).trim();
            default -> englishSummary + "\n\nRequested language: " + languageName(language) + ".";
        };
    }

    private String buildHighlights(List<String> abnormalLines, List<String> normalLines) {
        List<String> highlights = new ArrayList<>();
        abnormalLines.stream().limit(4).forEach(highlights::add);
        if (highlights.isEmpty()) {
            normalLines.stream().limit(3).forEach(highlights::add);
        }
        return String.join("\n", highlights);
    }

    private String interpret(ParsedFindingCandidate finding) {
        if (finding.hasNumericValue() && finding.hasPrintedRange()) {
            BigDecimal value = finding.getNumericValue();
            if (value.compareTo(finding.getPrintedRangeLow()) < 0) {
                return "lower than the printed lab range";
            }
            if (value.compareTo(finding.getPrintedRangeHigh()) > 0) {
                return "higher than the printed lab range";
            }
            return "within the printed lab range";
        }

        if (finding.hasDescriptiveValue()) {
            String value = finding.getDescriptiveValue().toLowerCase(Locale.ROOT);
            if (value.contains("negative") || value.contains("non-reactive")) {
                return "negative on the report";
            }
            if (value.contains("positive") || value.contains("reactive")) {
                return "positive on the report";
            }
            if (value.contains("normal")) {
                return "marked as normal";
            }
            return "marked as " + finding.getDescriptiveValue().toLowerCase(Locale.ROOT);
        }

        if (finding.hasNumericValue()) {
            return "needs review because no normal range was printed beside the value";
        }
        return "needs review";
    }

    private String listForLanguage(List<String> lines, String fallback) {
        if (lines.isEmpty()) {
            return "- " + fallback;
        }
        return lines.stream().limit(3).map(line -> "- " + line).collect(Collectors.joining("\n"));
    }

    private String buildLowConfidenceSummary(String language) {
        return switch (language.toLowerCase(Locale.ROOT)) {
            case "hi" -> """
                    हम इस image से रिपोर्ट का text साफ़ तरीके से नहीं पढ़ पाए.
                    कृपया report की सीधी, साफ़ photo या PDF upload करें. बेहतर result के लिए background crop करें, photo को घुमाकर सीधा रखें, और shadow से बचें.
                    """.trim();
            case "ta" -> """
                    இந்த image-இல் உள்ள report text-ஐ நாங்கள் நம்பகமாக படிக்க முடியவில்லை.
                    தயவுசெய்து report-ஐ நேராக எடுத்த தெளிவான photo அல்லது PDF upload செய்யுங்கள். பின்னணி crop செய்து, shadow இல்லாமல் மீண்டும் முயற்சிக்கவும்.
                    """.trim();
            case "te" -> """
                    ఈ image నుండి report text‌ను నమ్మకంగా చదవలేకపోయాము.
                    దయచేసి report‌ను సూటిగా తీసిన స్పష్టమైన photo లేదా PDF‌గా upload చేయండి. background crop చేసి, shadow లేకుండా మళ్లీ ప్రయత్నించండి.
                    """.trim();
            default -> """
                    We could not read this report image reliably enough to create a safe plain-language explanation.
                    Please upload a straight, well-lit photo or a PDF. Crop the background, keep the page flat, and avoid shadows for better OCR.
                    """.trim();
        };
    }

    private String buildLowConfidenceHighlights() {
        return String.join("\n",
                "The uploaded image could not be read clearly enough for safe explanation.",
                "Try a straight photo or PDF with the report filling most of the frame.",
                "Avoid shadows, tilted pages, and extra background around the report."
        );
    }

    private String toPatientSentence(ParsedFindingCandidate finding, String interpretation) {
        if (finding.hasNumericValue() && finding.hasPrintedRange()) {
            return "%s is %s%s, which is %s of %s."
                    .formatted(
                            finding.getParameterName(),
                            finding.getPatientValue(),
                            StringUtils.hasText(finding.getUnit()) ? " " + finding.getUnit() : "",
                            interpretation.replace("printed ", ""),
                            finding.getPrintedRangeText()
                    );
        }

        if (finding.hasDescriptiveValue()) {
            return "%s is marked as %s on the report."
                    .formatted(finding.getParameterName(), finding.getDescriptiveValue().toLowerCase(Locale.ROOT));
        }

        if (finding.hasNumericValue()) {
            return "%s is %s%s, but the report text did not clearly show a normal range beside it."
                    .formatted(
                            finding.getParameterName(),
                            finding.getPatientValue(),
                            StringUtils.hasText(finding.getUnit()) ? " " + finding.getUnit() : ""
                    );
        }

        return finding.getParameterName() + ": " + interpretation + ".";
    }

    private String toSimpleFindingLine(ParsedFindingCandidate finding) {
        StringBuilder builder = new StringBuilder(finding.getParameterName())
                .append(": ")
                .append(finding.getPatientValue());
        if (StringUtils.hasText(finding.getUnit())) {
            builder.append(" ").append(finding.getUnit());
        }
        if (finding.hasPrintedRange()) {
            builder.append(" | range ").append(finding.getPrintedRangeText());
        }
        if (finding.hasDescriptiveValue()) {
            builder.append(" | ").append(finding.getDescriptiveValue());
        }
        return builder.toString();
    }

    private String normalizeKey(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String languageName(String code) {
        return switch (code.toLowerCase(Locale.ROOT)) {
            case "hi" -> "Hindi";
            case "ta" -> "Tamil";
            case "te" -> "Telugu";
            default -> "English";
        };
    }

    private boolean isReliableFinding(ParsedFindingCandidate finding) {
        return finding.hasPrintedRange()
                || finding.hasDescriptiveValue()
                || containsMedicalKeyword(finding.getParameterName());
    }

    private boolean containsMedicalKeyword(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return MEDICAL_HINT_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    private boolean isMostlyNoise(String line) {
        int usefulCharacters = 0;
        int noisyCharacters = 0;
        for (char character : line.toCharArray()) {
            if (Character.isLetterOrDigit(character)) {
                usefulCharacters++;
            } else if (!Character.isWhitespace(character) && ".,:/%()-".indexOf(character) < 0) {
                noisyCharacters++;
            }
        }
        return usefulCharacters < 4 || noisyCharacters > usefulCharacters;
    }
}

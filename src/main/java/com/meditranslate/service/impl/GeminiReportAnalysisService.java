package com.meditranslate.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meditranslate.config.MediTranslateProperties;
import com.meditranslate.dto.AiReportAnalysisResult;
import com.meditranslate.dto.ExtractionResult;
import com.meditranslate.dto.ParsedFindingCandidate;
import com.meditranslate.dto.SummaryResult;
import com.meditranslate.entity.ReportSourceType;
import com.meditranslate.service.AiReportAnalysisService;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class GeminiReportAnalysisService implements AiReportAnalysisService {

    private static final String DEFAULT_DISCLAIMER =
            "This explanation is for understanding your report more easily. Please confirm treatment decisions with a qualified doctor.";
    private static final Logger LOGGER = LoggerFactory.getLogger(GeminiReportAnalysisService.class);
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 2200;
    private static final Pattern RANGE_TEXT_PATTERN =
            Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*(?:-|to|–|—)\\s*(\\d+(?:\\.\\d+)?)");

    private final MediTranslateProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient = RestClient.builder().build();

    public GeminiReportAnalysisService(MediTranslateProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<AiReportAnalysisResult> analyze(ExtractionResult extractionResult, String targetLanguage) {
        if (!shouldUseGemini()) {
            return Optional.empty();
        }

        Exception structuredFailure = null;
        try {
            GeminiStructuredResponse response = requestAnalysisWithFallback(extractionResult, targetLanguage);
            if (response != null && !isEmptyResponse(response)) {
                LOGGER.info("Gemini analysis succeeded for source type {}", extractionResult.getSourceType());
                return Optional.of(toResult(response, targetLanguage));
            }
            structuredFailure = new IllegalStateException("Gemini returned no usable structured analysis.");
        } catch (Exception ex) {
            structuredFailure = ex;
            LOGGER.warn("Gemini structured analysis failed. Trying simpler fallback formats: {}", shorten(ex.getMessage()));
        }

        AiReportAnalysisResult delimited = requestDelimitedAnalysisWithFallback(extractionResult, targetLanguage);
        if (isUsable(delimited)) {
            LOGGER.info("Using Gemini delimited fallback analysis for source type {}", extractionResult.getSourceType());
            return Optional.of(delimited);
        }

        SummaryResult summaryOnly = requestSummaryOnlyWithFallback(extractionResult, targetLanguage);
        if (summaryOnly != null && StringUtils.hasText(summaryOnly.getMainSummary())) {
            LOGGER.info("Using Gemini summary-only fallback for source type {}", extractionResult.getSourceType());
            return Optional.of(new AiReportAnalysisResult("", List.of(), summaryOnly));
        }

        if (structuredFailure != null) {
            LOGGER.warn("Gemini analysis failed. Falling back to the local analysis pipeline: {}", shorten(structuredFailure.getMessage()));
        } else {
            LOGGER.warn("Gemini returned no usable analysis. Falling back to the local analysis pipeline.");
        }
        return Optional.empty();
    }

    private boolean shouldUseGemini() {
        return properties.getGemini().isEnabled()
                && StringUtils.hasText(properties.getGemini().getApiKey());
    }

    private GeminiStructuredResponse requestAnalysisWithFallback(ExtractionResult extractionResult, String targetLanguage) throws Exception {
        List<String> modelsToTry = buildModelSequence();
        Exception lastException = null;

        for (String model : modelsToTry) {
            for (int attempt = 1; attempt <= Math.max(1, properties.getGemini().getMaxAttemptsPerModel()); attempt++) {
                try {
                    LOGGER.info("Trying Gemini model {} (attempt {}/{})", model, attempt,
                            Math.max(1, properties.getGemini().getMaxAttemptsPerModel()));
                    return requestAnalysis(model, extractionResult, targetLanguage);
                } catch (JsonProcessingException ex) {
                    lastException = ex;
                    if (attempt >= Math.max(1, properties.getGemini().getMaxAttemptsPerModel())) {
                        LOGGER.warn("Gemini model {} returned malformed JSON on its final attempt: {}", model, shorten(ex.getOriginalMessage()));
                        break;
                    }
                    LOGGER.warn("Gemini model {} returned malformed JSON. Retrying with a fresh response.", model);
                    sleepBeforeRetry();
                } catch (RestClientResponseException ex) {
                    lastException = ex;
                    if (!isRetryable(ex.getStatusCode()) || attempt >= Math.max(1, properties.getGemini().getMaxAttemptsPerModel())) {
                        LOGGER.warn("Gemini model {} failed with HTTP {}: {}", model, ex.getStatusCode().value(), shorten(ex.getResponseBodyAsString()));
                        break;
                    }
                    LOGGER.warn("Gemini model {} is temporarily unavailable (HTTP {}). Retrying shortly.", model, ex.getStatusCode().value());
                    sleepBeforeRetry();
                } catch (Exception ex) {
                    lastException = ex;
                    LOGGER.warn("Gemini model {} failed on attempt {}: {}", model, attempt, ex.getMessage());
                    break;
                }
            }
        }

        if (lastException != null) {
            throw lastException;
        }
        return null;
    }

    private GeminiStructuredResponse requestAnalysis(String model, ExtractionResult extractionResult, String targetLanguage) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contents", List.of(Map.of("parts", buildPromptParts(extractionResult, targetLanguage))));
        payload.put("generationConfig", Map.of(
                "temperature", 0.1,
                "maxOutputTokens", DEFAULT_MAX_OUTPUT_TOKENS,
                "responseMimeType", "application/json",
                "responseJsonSchema", buildResponseSchema()
        ));

        String response = restClient.post()
                .uri(properties.getGemini().getBaseUrl() + "/" + model + ":generateContent")
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-goog-api-key", properties.getGemini().getApiKey())
                .body(payload)
                .retrieve()
                .body(String.class);

        String jsonText = extractJsonText(response);
        if (!StringUtils.hasText(jsonText)) {
            return null;
        }
        try {
            return objectMapper.readValue(jsonText, GeminiStructuredResponse.class);
        } catch (JsonProcessingException ex) {
            String repaired = repairJsonText(jsonText);
            if (StringUtils.hasText(repaired) && !repaired.equals(jsonText)) {
                return objectMapper.readValue(repaired, GeminiStructuredResponse.class);
            }
            throw ex;
        }
    }

    private List<String> buildModelSequence() {
        List<String> models = new ArrayList<>();
        if (StringUtils.hasText(properties.getGemini().getModel())) {
            models.add(properties.getGemini().getModel().trim());
        }
        if (StringUtils.hasText(properties.getGemini().getFallbackModel())) {
            String fallback = properties.getGemini().getFallbackModel().trim();
            if (models.stream().noneMatch(fallback::equals)) {
                models.add(fallback);
            }
        }
        return models;
    }

    private SummaryResult requestSummaryOnlyWithFallback(ExtractionResult extractionResult, String targetLanguage) {
        for (String model : buildModelSequence()) {
            for (int attempt = 1; attempt <= Math.max(1, properties.getGemini().getMaxAttemptsPerModel()); attempt++) {
                try {
                    LOGGER.info("Trying Gemini summary-only mode with model {} (attempt {}/{})", model, attempt,
                            Math.max(1, properties.getGemini().getMaxAttemptsPerModel()));
                    return requestSummaryOnly(model, extractionResult, targetLanguage);
                } catch (RestClientResponseException ex) {
                    if (!isRetryable(ex.getStatusCode()) || attempt >= Math.max(1, properties.getGemini().getMaxAttemptsPerModel())) {
                        LOGGER.warn("Gemini summary-only mode failed with HTTP {} for model {}: {}", ex.getStatusCode().value(), model, shorten(ex.getResponseBodyAsString()));
                        break;
                    }
                    sleepQuietly();
                } catch (Exception ex) {
                    LOGGER.warn("Gemini summary-only mode failed for model {}: {}", model, ex.getMessage());
                    break;
                }
            }
        }
        return null;
    }

    private AiReportAnalysisResult requestDelimitedAnalysisWithFallback(ExtractionResult extractionResult, String targetLanguage) {
        for (String model : buildModelSequence()) {
            for (int attempt = 1; attempt <= Math.max(1, properties.getGemini().getMaxAttemptsPerModel()); attempt++) {
                try {
                    LOGGER.info("Trying Gemini delimited fallback with model {} (attempt {}/{})", model, attempt,
                            Math.max(1, properties.getGemini().getMaxAttemptsPerModel()));
                    AiReportAnalysisResult result = requestDelimitedAnalysis(model, extractionResult, targetLanguage);
                    if (isUsable(result)) {
                        return result;
                    }
                } catch (RestClientResponseException ex) {
                    if (!isRetryable(ex.getStatusCode()) || attempt >= Math.max(1, properties.getGemini().getMaxAttemptsPerModel())) {
                        LOGGER.warn("Gemini delimited fallback failed with HTTP {} for model {}: {}",
                                ex.getStatusCode().value(), model, shorten(ex.getResponseBodyAsString()));
                        break;
                    }
                    sleepQuietly();
                } catch (Exception ex) {
                    LOGGER.warn("Gemini delimited fallback failed for model {}: {}", model, shorten(ex.getMessage()));
                    break;
                }
            }
        }
        return null;
    }

    private SummaryResult requestSummaryOnly(String model, ExtractionResult extractionResult, String targetLanguage) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contents", List.of(Map.of("parts", buildSummaryOnlyPromptParts(extractionResult, targetLanguage))));
        payload.put("generationConfig", Map.of(
                "temperature", 0.1,
                "maxOutputTokens", 900
        ));

        String response = restClient.post()
                .uri(properties.getGemini().getBaseUrl() + "/" + model + ":generateContent")
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-goog-api-key", properties.getGemini().getApiKey())
                .body(payload)
                .retrieve()
                .body(String.class);

        String text = extractPlainText(response);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        return parseSummaryOnlyText(text, targetLanguage);
    }

    private AiReportAnalysisResult requestDelimitedAnalysis(String model, ExtractionResult extractionResult, String targetLanguage) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contents", List.of(Map.of("parts", buildDelimitedPromptParts(extractionResult, targetLanguage))));
        payload.put("generationConfig", Map.of(
                "temperature", 0.1,
                "maxOutputTokens", 1300
        ));

        String response = restClient.post()
                .uri(properties.getGemini().getBaseUrl() + "/" + model + ":generateContent")
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-goog-api-key", properties.getGemini().getApiKey())
                .body(payload)
                .retrieve()
                .body(String.class);

        String text = extractPlainText(response);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        return parseDelimitedText(text, targetLanguage);
    }

    private List<Map<String, Object>> buildPromptParts(ExtractionResult extractionResult, String targetLanguage) throws IOException {
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("text", buildPrompt(targetLanguage, extractionResult)));

        if (shouldSendFileDirectly(extractionResult)) {
            Path filePath = Path.of(extractionResult.getStoredFilePath());
            String mimeType = resolveMimeType(filePath, extractionResult.getSourceType());
            String base64Data = Base64.getEncoder().encodeToString(Files.readAllBytes(filePath));
            parts.add(Map.of(
                    "inline_data", Map.of(
                            "mime_type", mimeType,
                            "data", base64Data
                    )
            ));
        } else if (StringUtils.hasText(extractionResult.getExtractedText())) {
            parts.add(Map.of("text", "Report text:\n" + trimForPrompt(extractionResult.getExtractedText(), 20000)));
        }

        return parts;
    }

    private List<Map<String, Object>> buildSummaryOnlyPromptParts(ExtractionResult extractionResult, String targetLanguage) throws IOException {
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("text", buildSummaryOnlyPrompt(targetLanguage, extractionResult)));

        if (shouldSendFileDirectly(extractionResult)) {
            Path filePath = Path.of(extractionResult.getStoredFilePath());
            String mimeType = resolveMimeType(filePath, extractionResult.getSourceType());
            String base64Data = Base64.getEncoder().encodeToString(Files.readAllBytes(filePath));
            parts.add(Map.of(
                    "inline_data", Map.of(
                            "mime_type", mimeType,
                            "data", base64Data
                    )
            ));
        } else if (StringUtils.hasText(extractionResult.getExtractedText())) {
            parts.add(Map.of("text", "Report text:\n" + trimForPrompt(extractionResult.getExtractedText(), 9000)));
        }

        return parts;
    }

    private List<Map<String, Object>> buildDelimitedPromptParts(ExtractionResult extractionResult, String targetLanguage) throws IOException {
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("text", buildDelimitedPrompt(targetLanguage, extractionResult)));

        if (shouldSendFileDirectly(extractionResult)) {
            Path filePath = Path.of(extractionResult.getStoredFilePath());
            String mimeType = resolveMimeType(filePath, extractionResult.getSourceType());
            String base64Data = Base64.getEncoder().encodeToString(Files.readAllBytes(filePath));
            parts.add(Map.of(
                    "inline_data", Map.of(
                            "mime_type", mimeType,
                            "data", base64Data
                    )
            ));
        } else if (StringUtils.hasText(extractionResult.getExtractedText())) {
            parts.add(Map.of("text", "Report text:\n" + trimForPrompt(extractionResult.getExtractedText(), 12000)));
        }

        return parts;
    }

    private String buildPrompt(String targetLanguage, ExtractionResult extractionResult) {
        String supportedLanguageNames = properties.getSupportedLanguages().stream()
                .map(this::languageName)
                .collect(Collectors.joining(", "));

        StringBuilder prompt = new StringBuilder("""
                You are MediTranslate, an assistant that simplifies medical reports for patients.

                Read the uploaded medical report and return only valid JSON matching the schema.

                Rules:
                1. Extract only real medical findings, lab tests, measurements, or clinically relevant descriptive results.
                2. Ignore addresses, barcodes, timestamps, phone numbers, signatures, certification text, page numbers, and branding.
                3. Prefer the lab-printed reference range shown in the report when available.
                4. For each finding, write ai_interpretation in simple English using phrases like:
                   - within the printed lab range
                   - lower than the printed lab range
                   - higher than the printed lab range
                   - negative on the report
                   - positive on the report
                   - needs review because no normal range was printed beside the value
                5. plain_language_summary must be written in %s.
                6. translated_summaries must contain short patient-friendly summaries in these languages: %s.
                7. Do not diagnose. Explain simply and safely.
                8. Use very simple wording for patients. Avoid phrases like "abnormal flag", "structured values", "parsed findings", or "clinically correlated".
                9. For a single test, explain:
                   - what the test is
                   - the user's value
                   - the normal range
                   - whether it looks normal or needs attention
                   - one short practical context note
                10. For English output, plain_language_summary should usually be 4 to 6 short sentences, not a single-line answer.
                11. extracted_report_text must be a cleaned medical excerpt only, not the full page, and should stay under about 1200 characters.
                12. Keep translated summaries concise: 1 to 3 short sentences each.
                13. If the image or PDF is unclear, return empty or minimal findings and say the report could not be read clearly.
                14. key_highlights must be 2 to 4 short complete bullet-style lines, not broken sentence fragments.
                """.formatted(languageName(targetLanguage), supportedLanguageNames));

        if (StringUtils.hasText(extractionResult.getOriginalText())) {
            prompt.append("\nUser provided typed text:\n")
                    .append(trimForPrompt(extractionResult.getOriginalText(), 6000))
                    .append('\n');
        }

        if (StringUtils.hasText(extractionResult.getExtractedText()) && shouldSendFileDirectly(extractionResult)) {
            prompt.append("\nLocal helper extraction text (may be noisy; use only if helpful):\n")
                    .append(trimForPrompt(extractionResult.getExtractedText(), 8000))
                    .append('\n');
        }

        return prompt.toString().trim();
    }

    private String buildSummaryOnlyPrompt(String targetLanguage, ExtractionResult extractionResult) {
        StringBuilder prompt = new StringBuilder("""
                You are MediTranslate, helping a patient understand a medical report.

                Do not return JSON.
                Return plain text only in this exact format:

                SUMMARY:
                <4 to 6 short patient-friendly sentences in %s>

                HIGHLIGHTS:
                - <short bullet>
                - <short bullet>
                - <short bullet>

                DISCLAIMER:
                <one short safety disclaimer>

                Rules:
                - Use very simple words.
                - If the report shows one main abnormal result, explain that first.
                - If the report is mostly normal, say that clearly.
                - If the image is not readable enough, say that clearly.
                - Do not diagnose.
                """.formatted(languageName(targetLanguage)));

        if (StringUtils.hasText(extractionResult.getOriginalText())) {
            prompt.append("\nUser provided typed text:\n")
                    .append(trimForPrompt(extractionResult.getOriginalText(), 4000))
                    .append('\n');
        }

        if (StringUtils.hasText(extractionResult.getExtractedText()) && shouldSendFileDirectly(extractionResult)) {
            prompt.append("\nLocal helper extraction text (may be noisy; use only if helpful):\n")
                    .append(trimForPrompt(extractionResult.getExtractedText(), 5000))
                    .append('\n');
        }

        return prompt.toString().trim();
    }

    private String buildDelimitedPrompt(String targetLanguage, ExtractionResult extractionResult) {
        StringBuilder prompt = new StringBuilder("""
                You are MediTranslate, helping a patient understand a medical report.

                Do not return JSON.
                Return plain text only in this exact format:

                SUMMARY:
                <4 to 6 short patient-friendly sentences in %s>

                HIGHLIGHTS:
                - <short complete bullet>
                - <short complete bullet>
                - <short complete bullet>

                FINDINGS:
                - <parameter name> | <patient value> | <printed normal range or NONE> | <unit or NONE> | <simple interpretation>

                DISCLAIMER:
                <one short safety disclaimer>

                Rules:
                - Include only real medical findings.
                - Ignore addresses, lab branding, dates, phone numbers, barcode numbers, and signatures.
                - Prefer the printed lab range from the report.
                - Use very simple wording for patients.
                - If the report is unclear, include only the findings you can read confidently.
                - If no finding can be read confidently, keep FINDINGS empty and say so in SUMMARY.
                """.formatted(languageName(targetLanguage)));

        if (StringUtils.hasText(extractionResult.getOriginalText())) {
            prompt.append("\nUser provided typed text:\n")
                    .append(trimForPrompt(extractionResult.getOriginalText(), 5000))
                    .append('\n');
        }

        if (StringUtils.hasText(extractionResult.getExtractedText()) && shouldSendFileDirectly(extractionResult)) {
            prompt.append("\nLocal helper extraction text (may be noisy; use only if helpful):\n")
                    .append(trimForPrompt(extractionResult.getExtractedText(), 6000))
                    .append('\n');
        }

        return prompt.toString().trim();
    }

    private Map<String, Object> buildResponseSchema() {
        Map<String, Object> translationProperties = new LinkedHashMap<>();
        for (String language : properties.getSupportedLanguages()) {
            translationProperties.put(language, Map.of(
                    "type", "string",
                    "description", "Patient-friendly summary in " + languageName(language) + "."
            ));
        }

        Map<String, Object> translationsSchema = new LinkedHashMap<>();
        translationsSchema.put("type", "object");
        translationsSchema.put("properties", translationProperties);
        translationsSchema.put("required", new ArrayList<>(translationProperties.keySet()));
        translationsSchema.put("additionalProperties", false);

        Map<String, Object> findingSchema = new LinkedHashMap<>();
        findingSchema.put("type", "object");
        findingSchema.put("properties", Map.of(
                "parameter_name", Map.of("type", "string"),
                "patient_value", Map.of("type", "string"),
                "numeric_value", Map.of("type", List.of("number", "null")),
                "unit", Map.of("type", List.of("string", "null")),
                "printed_range_text", Map.of("type", List.of("string", "null")),
                "printed_range_low", Map.of("type", List.of("number", "null")),
                "printed_range_high", Map.of("type", List.of("number", "null")),
                "descriptive_value", Map.of("type", List.of("string", "null")),
                "ai_interpretation", Map.of("type", "string"),
                "source_snippet", Map.of("type", List.of("string", "null"))
        ));
        findingSchema.put("required", List.of(
                "parameter_name", "patient_value", "numeric_value", "unit", "printed_range_text",
                "printed_range_low", "printed_range_high", "descriptive_value", "ai_interpretation", "source_snippet"
        ));
        findingSchema.put("additionalProperties", false);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", Map.of(
                "extracted_report_text", Map.of("type", "string"),
                "plain_language_summary", Map.of("type", "string"),
                "key_highlights", Map.of(
                        "type", "array",
                        "items", Map.of("type", "string")
                ),
                "disclaimer", Map.of("type", "string"),
                "translated_summaries", translationsSchema,
                "findings", Map.of(
                        "type", "array",
                        "items", findingSchema
                ),
                "confidence_note", Map.of("type", List.of("string", "null"))
        ));
        root.put("required", List.of(
                "extracted_report_text", "plain_language_summary", "key_highlights",
                "disclaimer", "translated_summaries", "findings", "confidence_note"
        ));
        root.put("additionalProperties", false);
        return root;
    }

    private String extractJsonText(String rawResponse) throws IOException {
        JsonNode root = objectMapper.readTree(rawResponse);
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            return "";
        }
        JsonNode parts = candidates.get(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            return "";
        }
        for (JsonNode part : parts) {
            String text = part.path("text").asText("");
            if (StringUtils.hasText(text)) {
                return extractPrimaryJsonObject(stripCodeFences(text));
            }
        }
        return "";
    }

    private String extractPlainText(String rawResponse) throws IOException {
        JsonNode root = objectMapper.readTree(rawResponse);
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            return "";
        }
        JsonNode parts = candidates.get(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : parts) {
            String piece = part.path("text").asText("");
            if (StringUtils.hasText(piece)) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(piece.trim());
            }
        }
        return text.toString().trim();
    }

    private SummaryResult parseSummaryOnlyText(String text, String targetLanguage) {
        String summary = extractSection(text, "SUMMARY:", "HIGHLIGHTS:");
        String highlightsBlock = extractSection(text, "HIGHLIGHTS:", "DISCLAIMER:");
        String disclaimer = extractSection(text, "DISCLAIMER:", null);

        if (!StringUtils.hasText(summary)) {
            return null;
        }

        SummaryResult result = new SummaryResult();
        result.setMainSummary(summary.trim());
        result.setKeyHighlights(normalizeHighlights(highlightsBlock));
        result.setDisclaimer(StringUtils.hasText(disclaimer) ? disclaimer.trim() : DEFAULT_DISCLAIMER);
        result.setAiInterpretationsByParameter(Map.of());

        Map<String, String> translations = new LinkedHashMap<>();
        for (String language : properties.getSupportedLanguages()) {
            translations.put(language, "en".equalsIgnoreCase(language) ? summary.trim() : summary.trim());
        }
        translations.put(targetLanguage, summary.trim());
        result.setTranslatedSummaries(translations);
        return result;
    }

    private AiReportAnalysisResult parseDelimitedText(String text, String targetLanguage) {
        String summary = extractSection(text, "SUMMARY:", "HIGHLIGHTS:");
        String highlightsBlock = extractSection(text, "HIGHLIGHTS:", "FINDINGS:");
        String findingsBlock = extractSection(text, "FINDINGS:", "DISCLAIMER:");
        String disclaimer = extractSection(text, "DISCLAIMER:", null);

        List<ParsedFindingCandidate> findings = parseDelimitedFindings(findingsBlock);
        if (!StringUtils.hasText(summary) && findings.isEmpty()) {
            return null;
        }

        String resolvedSummary = StringUtils.hasText(summary)
                ? summary.trim()
                : buildFallbackSummaryFromFindings(findings);

        SummaryResult summaryResult = new SummaryResult();
        summaryResult.setMainSummary(resolvedSummary);
        summaryResult.setKeyHighlights(normalizeHighlights(highlightsBlock));
        summaryResult.setDisclaimer(StringUtils.hasText(disclaimer) ? disclaimer.trim() : DEFAULT_DISCLAIMER);
        summaryResult.setAiInterpretationsByParameter(buildInterpretationMap(findingsBlock, findings));

        Map<String, String> translations = new LinkedHashMap<>();
        for (String language : properties.getSupportedLanguages()) {
            translations.put(language, resolvedSummary);
        }
        translations.put(targetLanguage, resolvedSummary);
        summaryResult.setTranslatedSummaries(translations);

        return new AiReportAnalysisResult("", findings, summaryResult);
    }

    private String extractSection(String text, String startMarker, String endMarker) {
        int start = text.indexOf(startMarker);
        if (start < 0) {
            return "";
        }
        start += startMarker.length();
        int end = endMarker == null ? text.length() : text.indexOf(endMarker, start);
        if (end < 0) {
            end = text.length();
        }
        return text.substring(start, end).trim();
    }

    private String normalizeHighlights(String highlightsBlock) {
        if (!StringUtils.hasText(highlightsBlock)) {
            return "AI-generated summary available for this report.";
        }
        return highlightsBlock.lines()
                .map(String::trim)
                .map(line -> line.replaceFirst("^-\\s*", ""))
                .map(line -> line.replaceFirst("^\\*\\s*", ""))
                .filter(StringUtils::hasText)
                .limit(4)
                .collect(Collectors.joining("\n"));
    }

    private List<ParsedFindingCandidate> parseDelimitedFindings(String findingsBlock) {
        if (!StringUtils.hasText(findingsBlock)) {
            return List.of();
        }

        List<ParsedFindingCandidate> findings = new ArrayList<>();
        for (String rawLine : findingsBlock.lines().toList()) {
            String line = rawLine.trim();
            if (!StringUtils.hasText(line)) {
                continue;
            }
            line = line.replaceFirst("^[-*]\\s*", "");
            ParsedFindingCandidate candidate = parseDelimitedFindingLine(line);
            if (candidate != null) {
                findings.add(candidate);
            }
        }
        return findings;
    }

    private ParsedFindingCandidate parseDelimitedFindingLine(String line) {
        String[] parts = line.split("\\s*\\|\\s*", 5);
        if (parts.length < 5 || !StringUtils.hasText(parts[0]) || !StringUtils.hasText(parts[1])) {
            return null;
        }

        ParsedFindingCandidate candidate = new ParsedFindingCandidate();
        candidate.setParameterName(parts[0].trim());
        candidate.setPatientValue(parts[1].trim());
        candidate.setRawLine(line);

        BigDecimal numericValue = tryParseDecimal(parts[1]);
        if (numericValue != null) {
            candidate.setNumericValue(numericValue);
        }

        String rangeText = normalizeOptionalField(parts[2]);
        if (StringUtils.hasText(rangeText)) {
            candidate.setPrintedRangeText(rangeText);
            Matcher matcher = RANGE_TEXT_PATTERN.matcher(rangeText);
            if (matcher.find()) {
                candidate.setPrintedRangeLow(new BigDecimal(matcher.group(1)));
                candidate.setPrintedRangeHigh(new BigDecimal(matcher.group(2)));
            }
        }

        String unit = normalizeOptionalField(parts[3]);
        if (StringUtils.hasText(unit)) {
            candidate.setUnit(unit);
        }
        return candidate;
    }

    private Map<String, String> buildInterpretationMap(String findingsBlock, List<ParsedFindingCandidate> findings) {
        Map<String, String> interpretations = new LinkedHashMap<>();
        if (!StringUtils.hasText(findingsBlock)) {
            return interpretations;
        }

        int findingIndex = 0;
        for (String rawLine : findingsBlock.lines().toList()) {
            String line = rawLine.trim().replaceFirst("^[-*]\\s*", "");
            String[] parts = line.split("\\s*\\|\\s*", 5);
            if (parts.length < 5 || findingIndex >= findings.size()) {
                continue;
            }
            interpretations.put(normalizeKey(findings.get(findingIndex).getParameterName()), parts[4].trim());
            findingIndex++;
        }
        return interpretations;
    }

    private String buildFallbackSummaryFromFindings(List<ParsedFindingCandidate> findings) {
        if (findings.isEmpty()) {
            return "";
        }
        ParsedFindingCandidate first = findings.get(0);
        if (StringUtils.hasText(first.getPrintedRangeText())) {
            return "This report includes " + first.getParameterName() + " with a value of "
                    + first.getPatientValue() + ", and the printed range is " + first.getPrintedRangeText() + ".";
        }
        return "This report includes " + first.getParameterName() + " with a value of " + first.getPatientValue() + ".";
    }

    private String normalizeOptionalField(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String trimmed = value.trim();
        return "none".equalsIgnoreCase(trimmed) ? "" : trimmed;
    }

    private BigDecimal tryParseDecimal(String value) {
        String normalized = normalizeOptionalField(value).replace(",", "");
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String stripCodeFences(String text) {
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```(?:json)?\\s*", "");
            cleaned = cleaned.replaceFirst("\\s*```\\s*$", "");
        }
        return cleaned.trim();
    }

    private String extractPrimaryJsonObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return text;
        }

        boolean inString = false;
        boolean escaping = false;
        int depth = 0;
        for (int index = start; index < text.length(); index++) {
            char current = text.charAt(index);
            if (escaping) {
                escaping = false;
                continue;
            }
            if (current == '\\') {
                if (inString) {
                    escaping = true;
                }
                continue;
            }
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (!inString) {
                if (current == '{') {
                    depth++;
                } else if (current == '}') {
                    depth--;
                    if (depth == 0) {
                        return text.substring(start, index + 1).trim();
                    }
                }
            }
        }
        return text.substring(start).trim();
    }

    private String repairJsonText(String rawJson) {
        String json = extractPrimaryJsonObject(stripCodeFences(rawJson));
        StringBuilder repaired = new StringBuilder(json.length() + 32);
        boolean inString = false;
        boolean escaping = false;

        for (int index = 0; index < json.length(); index++) {
            char current = json.charAt(index);

            if (inString) {
                if (escaping) {
                    if (isValidJsonEscape(current)) {
                        repaired.append('\\').append(current);
                    } else {
                        repaired.append('\\').append('\\').append(current);
                    }
                    escaping = false;
                    continue;
                }

                if (current == '\\') {
                    escaping = true;
                    continue;
                }

                if (current == '"') {
                    inString = false;
                    repaired.append(current);
                    continue;
                }

                if (current == '\n') {
                    repaired.append("\\n");
                    continue;
                }
                if (current == '\r') {
                    repaired.append("\\r");
                    continue;
                }
                if (current == '\t') {
                    repaired.append("\\t");
                    continue;
                }

                repaired.append(current);
                continue;
            }

            repaired.append(current);
            if (current == '"') {
                inString = true;
            }
        }

        if (escaping) {
            repaired.append('\\');
        }

        return repaired.toString();
    }

    private boolean isValidJsonEscape(char value) {
        return value == '"' || value == '\\' || value == '/' || value == 'b'
                || value == 'f' || value == 'n' || value == 'r' || value == 't' || value == 'u';
    }

    private AiReportAnalysisResult toResult(GeminiStructuredResponse response, String targetLanguage) {
        SummaryResult summaryResult = new SummaryResult();

        Map<String, String> translations = new LinkedHashMap<>();
        for (String language : properties.getSupportedLanguages()) {
            String translated = response.translated_summaries == null ? null : response.translated_summaries.get(language);
            translations.put(language, StringUtils.hasText(translated) ? translated.trim() : response.plain_language_summary);
        }
        if (!translations.containsKey(targetLanguage)) {
            translations.put(targetLanguage, response.plain_language_summary);
        }

        enhanceShortSummaryIfNeeded(translations, deduceFindings(response.findings), targetLanguage);
        summaryResult.setTranslatedSummaries(translations);
        summaryResult.setMainSummary(resolveMainSummary(translations, targetLanguage, response.plain_language_summary));
        summaryResult.setKeyHighlights(resolveKeyHighlights(response));
        summaryResult.setDisclaimer(StringUtils.hasText(response.disclaimer) ? response.disclaimer.trim() : DEFAULT_DISCLAIMER);

        List<ParsedFindingCandidate> candidates = new ArrayList<>();
        Map<String, ParsedFindingCandidate> deduped = new LinkedHashMap<>();
        Map<String, String> interpretations = new LinkedHashMap<>();

        for (GeminiFinding finding : safeList(response.findings)) {
            ParsedFindingCandidate candidate = toCandidate(finding);
            if (candidate == null || !StringUtils.hasText(candidate.getParameterName())) {
                continue;
            }
            String key = normalizeKey(candidate.getParameterName());
            deduped.putIfAbsent(key, candidate);
            interpretations.putIfAbsent(key, StringUtils.hasText(finding.ai_interpretation)
                    ? finding.ai_interpretation.trim()
                    : "needs review");
        }

        candidates.addAll(deduped.values());
        summaryResult.setAiInterpretationsByParameter(interpretations);

        String extractedReportText = StringUtils.hasText(response.extracted_report_text)
                ? response.extracted_report_text.trim()
                : "";

        return new AiReportAnalysisResult(extractedReportText, candidates, summaryResult);
    }

    private ParsedFindingCandidate toCandidate(GeminiFinding finding) {
        if (finding == null || !StringUtils.hasText(finding.parameter_name)) {
            return null;
        }

        ParsedFindingCandidate candidate = new ParsedFindingCandidate();
        candidate.setParameterName(finding.parameter_name.trim());
        candidate.setPatientValue(StringUtils.hasText(finding.patient_value)
                ? finding.patient_value.trim()
                : defaultPatientValue(finding.numeric_value, finding.descriptive_value));
        candidate.setUnit(StringUtils.hasText(finding.unit) ? finding.unit.trim() : null);
        candidate.setRawLine(StringUtils.hasText(finding.source_snippet)
                ? finding.source_snippet.trim()
                : buildRawLineFallback(finding));

        if (finding.numeric_value != null) {
            candidate.setNumericValue(BigDecimal.valueOf(finding.numeric_value));
        }
        if (finding.printed_range_low != null) {
            candidate.setPrintedRangeLow(BigDecimal.valueOf(finding.printed_range_low));
        }
        if (finding.printed_range_high != null) {
            candidate.setPrintedRangeHigh(BigDecimal.valueOf(finding.printed_range_high));
        }

        if (StringUtils.hasText(finding.printed_range_text)) {
            candidate.setPrintedRangeText(finding.printed_range_text.trim());
        } else if (finding.printed_range_low != null && finding.printed_range_high != null) {
            candidate.setPrintedRangeText(finding.printed_range_low + " - " + finding.printed_range_high);
        }

        if (StringUtils.hasText(finding.descriptive_value)) {
            candidate.setDescriptiveValue(finding.descriptive_value.trim());
        }
        return candidate;
    }

    private boolean isEmptyResponse(GeminiStructuredResponse response) {
        return !StringUtils.hasText(response.extracted_report_text)
                && !StringUtils.hasText(response.plain_language_summary)
                && (response.findings == null || response.findings.isEmpty());
    }

    private boolean isUsable(AiReportAnalysisResult result) {
        return result != null && (result.hasParsedFindings() || result.hasSummary());
    }

    private boolean shouldSendFileDirectly(ExtractionResult extractionResult) {
        if (!StringUtils.hasText(extractionResult.getStoredFilePath())) {
            return false;
        }
        if (extractionResult.getSourceType() == ReportSourceType.IMAGE_UPLOAD) {
            return true;
        }
        Path path = Path.of(extractionResult.getStoredFilePath());
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private String resolveMimeType(Path filePath, ReportSourceType sourceType) throws IOException {
        String mimeType = Files.probeContentType(filePath);
        if (StringUtils.hasText(mimeType)) {
            return mimeType;
        }
        if (sourceType == ReportSourceType.IMAGE_UPLOAD) {
            String filename = filePath.getFileName().toString().toLowerCase(Locale.ROOT);
            if (filename.endsWith(".png")) {
                return "image/png";
            }
            if (filename.endsWith(".bmp")) {
                return "image/bmp";
            }
            if (filename.endsWith(".webp")) {
                return "image/webp";
            }
            return "image/jpeg";
        }
        return "application/pdf";
    }

    private String defaultPatientValue(Double numericValue, String descriptiveValue) {
        if (numericValue != null) {
            return BigDecimal.valueOf(numericValue).stripTrailingZeros().toPlainString();
        }
        if (StringUtils.hasText(descriptiveValue)) {
            return descriptiveValue.trim();
        }
        return "";
    }

    private String buildRawLineFallback(GeminiFinding finding) {
        StringBuilder builder = new StringBuilder(finding.parameter_name);
        if (StringUtils.hasText(finding.patient_value)) {
            builder.append(": ").append(finding.patient_value);
        }
        if (StringUtils.hasText(finding.unit)) {
            builder.append(" ").append(finding.unit);
        }
        if (StringUtils.hasText(finding.printed_range_text)) {
            builder.append(" | range ").append(finding.printed_range_text);
        }
        return builder.toString();
    }

    private String trimForPrompt(String value, int maxLength) {
        if (!StringUtils.hasText(value) || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength);
    }

    private String languageName(String code) {
        return switch (code.toLowerCase(Locale.ROOT)) {
            case "hi" -> "Hindi";
            case "ta" -> "Tamil";
            case "te" -> "Telugu";
            default -> "English";
        };
    }

    private String normalizeKey(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private void enhanceShortSummaryIfNeeded(Map<String, String> translations, List<GeminiFinding> findings, String targetLanguage) {
        if (!"en".equalsIgnoreCase(targetLanguage)) {
            return;
        }
        String english = translations.get("en");
        if (!StringUtils.hasText(english) || !isTooShortForPatientSummary(english) || findings.size() != 1) {
            return;
        }

        String improved = buildDetailedEnglishSummary(findings.get(0));
        if (!StringUtils.hasText(improved)) {
            return;
        }
        translations.put("en", improved);
        translations.put(targetLanguage, improved);
    }

    private boolean isTooShortForPatientSummary(String summary) {
        String normalized = summary.replaceAll("\\s+", " ").trim();
        int sentenceCount = normalized.split("[.!?]+").length;
        return normalized.length() < 180 || sentenceCount < 3;
    }

    private String buildDetailedEnglishSummary(GeminiFinding finding) {
        if (finding == null || !StringUtils.hasText(finding.parameter_name) || !StringUtils.hasText(finding.patient_value)) {
            return "";
        }

        String rangeText = StringUtils.hasText(finding.printed_range_text)
                ? finding.printed_range_text.trim()
                : null;
        String unitSuffix = StringUtils.hasText(finding.unit) ? " " + finding.unit.trim() : "";
        String testMeaning = testMeaning(finding.parameter_name);
        String rangeSentence;
        String statusSentence;

        if (rangeText != null) {
            rangeSentence = "Your value is %s%s, and the lab's normal range is %s."
                    .formatted(finding.patient_value.trim(), unitSuffix, rangeText);
        } else {
            rangeSentence = "Your value is %s%s."
                    .formatted(finding.patient_value.trim(), unitSuffix);
        }

        String interpretation = StringUtils.hasText(finding.ai_interpretation)
                ? finding.ai_interpretation.toLowerCase(Locale.ROOT)
                : "";
        if (interpretation.contains("within")) {
            statusSentence = "This result looks normal because it is inside the range shown on the report.";
        } else if (interpretation.contains("higher") || interpretation.contains("high")) {
            statusSentence = "This result looks higher than the normal range shown on the report.";
        } else if (interpretation.contains("lower") || interpretation.contains("low")) {
            statusSentence = "This result looks lower than the normal range shown on the report.";
        } else {
            statusSentence = "This result should be reviewed together with the rest of the report.";
        }

        String contextSentence = interpretation.contains("within")
                ? "In simple words, this number does not show an obvious problem by itself, though doctors still read it together with symptoms and any related tests."
                : "In simple words, this number may need attention, but doctors usually interpret it together with symptoms, history, and sometimes related tests.";

        return """
                This report mainly shows your %s test result.
                %s
                %s
                %s
                %s
                """.formatted(
                finding.parameter_name.trim(),
                rangeSentence,
                StringUtils.hasText(testMeaning) ? testMeaning : "This test helps doctors understand how this part of the body is functioning.",
                statusSentence,
                contextSentence
        ).replaceAll("\\s+\n", "\n").trim();
    }

    private String testMeaning(String parameterName) {
        String normalized = parameterName.toLowerCase(Locale.ROOT);
        if (normalized.contains("tsh")) {
            return "TSH is a thyroid-related test that helps check how your body is signaling the thyroid gland to work.";
        }
        if (normalized.contains("hemoglobin") || normalized.equals("hb")) {
            return "Hemoglobin helps show how well your blood carries oxygen.";
        }
        if (normalized.contains("rbc")) {
            return "RBC means red blood cell count, which is part of checking your blood health.";
        }
        if (normalized.contains("wbc")) {
            return "WBC means white blood cell count, which can help show infection or inflammation patterns.";
        }
        if (normalized.contains("platelet")) {
            return "Platelets help your blood clot properly.";
        }
        if (normalized.contains("glucose") || normalized.contains("sugar")) {
            return "This test helps show your blood sugar level.";
        }
        if (normalized.contains("creatinine")) {
            return "Creatinine helps doctors check kidney function.";
        }
        if (normalized.contains("cholesterol")) {
            return "This test helps doctors understand part of your heart and blood vessel risk profile.";
        }
        if (normalized.contains("vitamin d")) {
            return "Vitamin D is related to bone health and several body functions.";
        }
        return "";
    }

    private List<GeminiFinding> deduceFindings(List<GeminiFinding> findings) {
        return findings == null ? List.of() : findings;
    }

    private boolean isRetryable(HttpStatusCode statusCode) {
        int value = statusCode.value();
        return value == 429 || value == 500 || value == 503 || value == 504;
    }

    private void sleepBeforeRetry() throws InterruptedException {
        long delayMs = Math.max(250L, properties.getGemini().getRetryDelayMs());
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ex;
        }
    }

    private void sleepQuietly() {
        try {
            sleepBeforeRetry();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private String shorten(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 220 ? normalized : normalized.substring(0, 220) + "...";
    }

    private String resolveMainSummary(Map<String, String> translations, String targetLanguage, String plainSummary) {
        String translated = translations.get(targetLanguage);
        if (StringUtils.hasText(translated)) {
            return translated;
        }
        if (StringUtils.hasText(plainSummary)) {
            return plainSummary.trim();
        }
        return translations.values().stream().filter(StringUtils::hasText).findFirst().orElse("");
    }

    private String resolveKeyHighlights(GeminiStructuredResponse response) {
        List<String> highlights = safeList(response.key_highlights).stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        if (!highlights.isEmpty()) {
            return String.join("\n", highlights);
        }
        if (StringUtils.hasText(response.confidence_note)) {
            return response.confidence_note.trim();
        }
        return "AI-generated summary available for this report.";
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    public static class GeminiStructuredResponse {
        public String extracted_report_text;
        public String plain_language_summary;
        public List<String> key_highlights = new ArrayList<>();
        public String disclaimer;
        public Map<String, String> translated_summaries = new LinkedHashMap<>();
        public List<GeminiFinding> findings = new ArrayList<>();
        public String confidence_note;
    }

    public static class GeminiFinding {
        public String parameter_name;
        public String patient_value;
        public Double numeric_value;
        public String unit;
        public String printed_range_text;
        public Double printed_range_low;
        public Double printed_range_high;
        public String descriptive_value;
        public String ai_interpretation;
        public String source_snippet;
    }
}

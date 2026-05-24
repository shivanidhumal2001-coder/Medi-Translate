package com.meditranslate.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meditranslate.config.MediTranslateProperties;
import com.meditranslate.dto.AiReportAnalysisResult;
import com.meditranslate.dto.ParsedFindingCandidate;
import com.meditranslate.dto.SummaryResult;
import com.meditranslate.service.AiAnalysisService;
import com.meditranslate.util.RichSummaryHtmlBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.*;

/**
 * ClaudeAiService — uses Claude Vision API to:
 *  1. Directly read medical report images (no Tesseract needed)
 *  2. Extract text from any uploaded document
 *  3. Generate rich plain-language explanations with causes, cures, urgency
 */
@Service
public class ClaudeAiService implements AiAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeAiService.class);

    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String MODEL = "claude-3-5-sonnet-20241022";

    private final MediTranslateProperties props;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public ClaudeAiService(MediTranslateProperties props) {
        this.props = props;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Analyze plain text medical report.
     */
    @Override
    public AiReportAnalysisResult analyzeText(String reportText, String targetLanguage) {
        String prompt = buildComprehensivePrompt(reportText, targetLanguage);
        String jsonResponse = callClaude(prompt, null, null);
        return parseClaudeResponse(reportText, jsonResponse, targetLanguage);
    }

    /**
     * Analyze medical report image using Claude Vision — NO Tesseract required.
     * Claude reads the image directly.
     */
    @Override
    public AiReportAnalysisResult analyzeImage(byte[] imageBytes, String mimeType, String targetLanguage) {
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);
        String prompt = buildImagePrompt(targetLanguage);
        String jsonResponse = callClaude(prompt, base64Image, mimeType);
        return parseClaudeResponse("[Image uploaded — Claude extracted text directly]", jsonResponse, targetLanguage);
    }

    /**
     * Extract text from image using Claude Vision.
     * Use this when you need only the OCR text (no analysis).
     */
    @Override
    public String extractTextFromImage(byte[] imageBytes, String mimeType) {
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);
        String prompt = "You are a medical OCR system. Please extract ALL text from this medical report image exactly as it appears. Include every number, unit, test name, result value, reference range, and patient information. Return ONLY the extracted text, nothing else.";
        return callClaude(prompt, base64Image, mimeType);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PROMPT BUILDERS
    // ─────────────────────────────────────────────────────────────────────────

    private String buildImagePrompt(String targetLanguage) {
        return buildFullAnalysisPromptWithPrefix(
            "You are a compassionate medical report assistant helping patients understand their medical reports.\n" +
            "An image of a medical report has been provided. First, read all text in the image carefully. " +
            "Then analyze it completely and respond with the JSON structure described below.",
            targetLanguage
        );
    }

    private String buildComprehensivePrompt(String reportText, String targetLanguage) {
        return buildFullAnalysisPromptWithPrefix(
            "You are a compassionate medical report assistant helping patients understand their medical reports.\n\n" +
            "MEDICAL REPORT TEXT:\n" +
            "---\n" + reportText + "\n---\n",
            targetLanguage
        );
    }

    private String buildFullAnalysisPromptWithPrefix(String prefix, String targetLanguage) {
        String langName = getLanguageName(targetLanguage);
        return prefix + """

Analyze this medical report and respond ONLY with valid JSON in this exact structure:

{
  "extractedText": "Full text extracted from the report (if image input)",
  "simpleSummary": "A warm, clear explanation in simple language (like explaining to a 12-year-old). Explain what each test is, what the patient's values mean, which ones are normal and which need attention. Use friendly, non-scary language. Mention the patient's name if visible.",
  "whatIsHappening": "In 2-3 sentences: what is going on in the patient's body right now based on these results?",
  "whyDidThisHappen": "Common causes or reasons why these results might be abnormal. If all normal, explain why all is well.",
  "howToCure": "Practical, safe advice: dietary changes, lifestyle improvements, when to consult a doctor, follow-up tests needed. Be specific and actionable.",
  "keyHighlights": "Bullet points (one per line starting with •) of the most important findings. Mark abnormal ones with ⚠️",
  "urgencyLevel": "ONE of: LOW / MEDIUM / HIGH / CRITICAL",
  "urgencyReason": "One sentence explaining why this urgency level was chosen",
  "trustScore": 0.85,
  "trustReason": "Brief explanation of why this trust score was given (based on clarity of report, completeness of data)",
  "disclaimer": "This is an AI-generated explanation to help you understand your report. Always consult your doctor for medical decisions.",
  "suggestedQuestions": [
    "What does low hemoglobin mean for my daily life?",
    "Do I need iron supplements?",
    "When should I repeat this blood test?"
  ],
  "findings": [
    {
      "parameterName": "Haemoglobin",
      "patientValue": "11.7",
      "unit": "gm/dl",
      "normalRange": "12 to 16",
      "status": "LOW",
      "aiInterpretation": "Your hemoglobin is slightly low — this means your blood is carrying a little less oxygen than ideal. You may feel tired or breathless.",
      "layer": "PRINTED_RANGE"
    }
  ],
  "translatedSummary_""" + targetLanguage + """
": "Provide the simpleSummary translated into """ + langName + """
. If language is 'en', repeat the English summary.",
  "translatedSummary_hi": "हिंदी में सरल भाषा में रिपोर्ट की व्याख्या करें।",
  "translatedSummary_mr": "मराठी मध्ये सोप्या भाषेत अहवालाचे स्पष्टीकरण द्या।",
  "translatedSummary_ta": "தமிழில் எளிய மொழியில் அறிக்கையை விளக்கவும்.",
  "translatedSummary_te": "తెలుగులో సులభ భాషలో నివేదికను వివరించండి."
}

RULES:
- findings[].status must be exactly one of: NORMAL / LOW / HIGH / ABNORMAL / CRITICAL / UNKNOWN
- urgencyLevel must be exactly one of: LOW / MEDIUM / HIGH / CRITICAL  
- trustScore must be a decimal between 0.0 and 1.0
- findings[].layer must be: PRINTED_RANGE / WHO_REFERENCE / DESCRIPTIVE_RULE
- If a finding value is within the printed reference range → status = NORMAL, layer = PRINTED_RANGE
- If a finding value is outside range → status = LOW or HIGH accordingly
- Write simpleSummary, whatIsHappening, whyDidThisHappen, howToCure, urgencyReason, trustReason, disclaimer, and suggestedQuestions in """ + langName + """
- Keep all explanatory narrative in the selected output language above, not in English unless the selected language is English.
- Be warm, human, and encouraging. Patients are often scared — reassure them while being honest.
- For howToCure: include specific foods, habits, and doctor visit timing
- Always include at least 3 suggestedQuestions relevant to this specific report
- Response must be pure JSON — no markdown, no code fences, no extra text
""";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CLAUDE API CALL
    // ─────────────────────────────────────────────────────────────────────────

    private String callClaude(String textPrompt, String base64Image, String mimeType) {
        String apiKey = props.getClaude().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Claude API key not configured. Set medi.translate.claude.api-key in application.properties");
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", ANTHROPIC_VERSION);

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", props.getClaude().getModel() != null ? props.getClaude().getModel() : MODEL);
            requestBody.put("max_tokens", 4096);

            List<Map<String, Object>> contentList = new ArrayList<>();

            // Add image if provided (Vision capability)
            if (base64Image != null && mimeType != null) {
                Map<String, Object> imageContent = new LinkedHashMap<>();
                imageContent.put("type", "image");
                Map<String, Object> imageSource = new LinkedHashMap<>();
                imageSource.put("type", "base64");
                imageSource.put("media_type", mimeType);
                imageSource.put("data", base64Image);
                imageContent.put("source", imageSource);
                contentList.add(imageContent);
            }

            // Add text prompt
            Map<String, Object> textContent = new LinkedHashMap<>();
            textContent.put("type", "text");
            textContent.put("text", textPrompt);
            contentList.add(textContent);

            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "user");
            message.put("content", contentList);

            requestBody.put("messages", List.of(message));

            String requestJson = objectMapper.writeValueAsString(requestBody);
            HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    CLAUDE_API_URL, HttpMethod.POST, entity, String.class);

            JsonNode responseNode = objectMapper.readTree(response.getBody());
            return responseNode
                    .path("content")
                    .get(0)
                    .path("text")
                    .asText();

        } catch (Exception e) {
            log.error("Error calling Claude API", e);
            throw new RuntimeException("Claude API call failed: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RESPONSE PARSER
    // ─────────────────────────────────────────────────────────────────────────

    private AiReportAnalysisResult parseClaudeResponse(String originalText, String claudeJson, String targetLanguage) {
        try {
            // Strip markdown code fences if Claude wraps in them despite instructions
            String cleanJson = claudeJson.trim();
            if (cleanJson.startsWith("```")) {
                cleanJson = cleanJson.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
            }

            JsonNode root = objectMapper.readTree(cleanJson);

            // ── SummaryResult ──────────────────────────────────────────────
            SummaryResult summary = new SummaryResult();

            String mainSummary = buildRichSummary(root, targetLanguage);
            summary.setMainSummary(mainSummary);

            String highlights = root.path("keyHighlights").asText("");
            summary.setKeyHighlights(highlights);

            summary.setDisclaimer(root.path("disclaimer").asText(
                    "This is an AI-assisted explanation. Please consult your doctor for medical decisions."));

            // Trust score
            double trustScore = root.path("trustScore").asDouble(0.75);

            // Multilingual summaries
            Map<String, String> translations = new LinkedHashMap<>();
            for (String lang : List.of("en", "hi", "mr", "ta", "te")) {
                String key = "translatedSummary_" + lang;
                String val = root.path(key).asText("");
                if (!val.isBlank()) {
                    translations.put(lang, val);
                }
            }
            summary.setTranslatedSummaries(translations);

            // AI interpretations per parameter
            Map<String, String> aiInterps = new LinkedHashMap<>();
            JsonNode findings = root.path("findings");
            if (findings.isArray()) {
                for (JsonNode f : findings) {
                    String param = f.path("parameterName").asText("");
                    String interp = f.path("aiInterpretation").asText("");
                    if (!param.isBlank() && !interp.isBlank()) {
                        aiInterps.put(param, interp);
                    }
                }
            }
            summary.setAiInterpretationsByParameter(aiInterps);

            // ── ParsedFindings ─────────────────────────────────────────────
            List<ParsedFindingCandidate> parsedFindings = new ArrayList<>();
            if (findings.isArray()) {
                for (JsonNode f : findings) {
                    ParsedFindingCandidate candidate = new ParsedFindingCandidate();
                    candidate.setParameterName(f.path("parameterName").asText(""));
                    candidate.setPatientValue(f.path("patientValue").asText(""));
                    candidate.setUnit(f.path("unit").asText(""));
                    candidate.setPrintedRangeText(f.path("normalRange").asText(""));

                    // Try to parse numeric values
                    try {
                        String valStr = f.path("patientValue").asText("").replaceAll("[^0-9.]", "");
                        if (!valStr.isBlank()) {
                            candidate.setNumericValue(new BigDecimal(valStr));
                        }
                    } catch (NumberFormatException ignored) {}

                    // Try to parse range low/high from "normalRange" like "12 to 16"
                    String rangeText = f.path("normalRange").asText("");
                    parsePrintedRange(candidate, rangeText);

                    candidate.setDescriptiveValue(f.path("aiInterpretation").asText(""));
                    candidate.setRawLine(f.path("parameterName").asText("") + ": " +
                            f.path("patientValue").asText("") + " " + f.path("unit").asText(""));

                    parsedFindings.add(candidate);
                }
            }

            // ── Extracted text ─────────────────────────────────────────────
            String extractedText = root.path("extractedText").asText("");
            if (extractedText.isBlank()) {
                extractedText = originalText;
            }

            return new AiReportAnalysisResult(extractedText, parsedFindings, summary);

        } catch (Exception e) {
            log.error("Failed to parse Claude JSON response: {}", claudeJson, e);
            // Return a graceful fallback
            return buildFallbackResult(originalText, claudeJson);
        }
    }

    /**
     * Builds the rich main summary combining all sections.
     */
    private String buildRichSummary(JsonNode root, String targetLanguage) {
        return RichSummaryHtmlBuilder.buildHtml(
                root.path("simpleSummary").asText(""),
                root.path("whatIsHappening").asText(""),
                root.path("whyDidThisHappen").asText(""),
                root.path("howToCure").asText(""),
                root.path("urgencyLevel").asText(""),
                root.path("urgencyReason").asText(""),
                parseTrustScore(root.path("trustScore").asText("")),
                root.path("trustReason").asText(""),
                readQuestions(root.path("suggestedQuestions"))
        );
    }

    private Double parseTrustScore(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(rawValue);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private List<String> readQuestions(JsonNode questionsNode) {
        List<String> questions = new ArrayList<>();
        if (questionsNode != null && questionsNode.isArray()) {
            for (JsonNode question : questionsNode) {
                String text = question.asText("");
                if (!text.isBlank()) {
                    questions.add(text);
                }
            }
        }
        return questions;
    }

    private void parsePrintedRange(ParsedFindingCandidate candidate, String rangeText) {
        if (rangeText == null || rangeText.isBlank()) return;
        // Pattern: "12 to 16" or "12-16" or "12 – 16"
        String[] parts = rangeText.split("(?i)\\s*to\\s*|\\s*[-–]\\s*");
        if (parts.length == 2) {
            try {
                candidate.setPrintedRangeLow(new BigDecimal(parts[0].trim().replaceAll("[^0-9.]", "")));
                candidate.setPrintedRangeHigh(new BigDecimal(parts[1].trim().replaceAll("[^0-9.]", "")));
            } catch (NumberFormatException ignored) {}
        }
    }

    private AiReportAnalysisResult buildFallbackResult(String originalText, String rawResponse) {
        SummaryResult summary = new SummaryResult();
        summary.setMainSummary(rawResponse);
        summary.setKeyHighlights("• Report analyzed — please see the summary above.");
        summary.setDisclaimer("AI-assisted explanation. Consult your doctor for medical decisions.");
        return new AiReportAnalysisResult(originalText, List.of(), summary);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private String getLanguageName(String code) {
        return switch (code) {
            case "hi" -> "Hindi";
            case "mr" -> "Marathi";
            case "ta" -> "Tamil";
            case "te" -> "Telugu";
            case "bn" -> "Bengali";
            case "gu" -> "Gujarati";
            default  -> "English";
        };
    }

    @Override
    public boolean isAvailable() {
        String key = props.getClaude().getApiKey();
        return key != null && !key.isBlank();
    }
}

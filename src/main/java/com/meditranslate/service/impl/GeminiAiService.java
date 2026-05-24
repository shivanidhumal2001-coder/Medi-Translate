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
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.*;

public class GeminiAiService implements AiAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(GeminiAiService.class);

    private final MediTranslateProperties props;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public GeminiAiService(MediTranslateProperties props) {
        this.props = props;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public AiReportAnalysisResult analyzeText(String reportText, String targetLanguage) {
        String prompt = buildComprehensivePrompt(reportText, targetLanguage);
        String jsonResponse = callGeminiText(prompt);
        return parseResponse(reportText, jsonResponse, targetLanguage);
    }

    @Override
    public AiReportAnalysisResult analyzeImage(byte[] imageBytes, String mimeType, String targetLanguage) {
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);
        String prompt = buildImagePrompt(targetLanguage);
        String jsonResponse = callGeminiVision(prompt, base64Image, mimeType);
        return parseResponse("[Image uploaded — Gemini Vision extracted text]", jsonResponse, targetLanguage);
    }

    @Override
    public String extractTextFromImage(byte[] imageBytes, String mimeType) {
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);
        String prompt = "You are a medical OCR system. Extract ALL text from this medical report image "
                + "exactly as it appears. Include every number, unit, test name, result value, and reference range. "
                + "Return ONLY the extracted text, nothing else.";
        return callGeminiVision(prompt, base64Image, mimeType);
    }

    @Override
    public boolean isAvailable() {
        String key = props.getGemini().getApiKey();
        return key != null && !key.isBlank() && !key.equals("YOUR_GEMINI_KEY_HERE");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GEMINI API CALLS
    // ─────────────────────────────────────────────────────────────────────────

    private String callGeminiText(String prompt) {
        String url = buildUrl();
        Map<String, Object> requestBody = buildTextRequest(prompt);
        return doPost(url, requestBody);
    }

    private String callGeminiVision(String prompt, String base64Image, String mimeType) {
        String url = buildUrl();

        Map<String, Object> inlineData = new LinkedHashMap<>();
        inlineData.put("mime_type", mimeType);
        inlineData.put("data", base64Image);

        Map<String, Object> imagePart = new LinkedHashMap<>();
        imagePart.put("inline_data", inlineData);

        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("text", prompt);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("parts", List.of(imagePart, textPart));

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("temperature", 0.2);
        generationConfig.put("maxOutputTokens", 16384);
        generationConfig.put("responseMimeType", "application/json");

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("contents", List.of(content));
        requestBody.put("generationConfig", generationConfig);

        return doPost(url, requestBody);
    }

    private Map<String, Object> buildTextRequest(String prompt) {
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("text", prompt);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("parts", List.of(textPart));

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("temperature", 0.2);
        generationConfig.put("maxOutputTokens", 16384);
        generationConfig.put("responseMimeType", "application/json");

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("contents", List.of(content));
        requestBody.put("generationConfig", generationConfig);
        return requestBody;
    }

    private String doPost(String url, Map<String, Object> requestBody) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            String requestJson = objectMapper.writeValueAsString(requestBody);
            HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);

            String responseBody = response.getBody();
            if (!StringUtils.hasText(responseBody)) {
                throw new IllegalStateException("Gemini returned an empty response body");
            }

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode errorNode = root.path("error");
            if (!errorNode.isMissingNode() && !errorNode.isNull()) {
                String apiMessage = errorNode.path("message").asText("");
                throw new IllegalStateException("Gemini API error: "
                        + (StringUtils.hasText(apiMessage) ? apiMessage : shorten(responseBody)));
            }

            JsonNode firstCandidate = root.path("candidates").path(0);
            if (firstCandidate.isMissingNode() || firstCandidate.isNull()) {
                String blockReason = root.path("promptFeedback").path("blockReason").asText("");
                String finishReason = root.path("promptFeedback").path("finishReason").asText("");
                String detail = StringUtils.hasText(blockReason) ? "blockReason=" + blockReason
                        : (StringUtils.hasText(finishReason) ? "finishReason=" + finishReason : shorten(responseBody));
                throw new IllegalStateException("Gemini returned no candidates: " + detail);
            }

            JsonNode firstPart = firstCandidate.path("content").path("parts").path(0);
            String text = firstPart.path("text").asText("");
            if (!StringUtils.hasText(text)) {
                String finishReason = firstCandidate.path("finishReason").asText("");
                String detail = StringUtils.hasText(finishReason) ? "finishReason=" + finishReason : shorten(responseBody);
                throw new IllegalStateException("Gemini candidate did not contain text output: " + detail);
            }

            return text;

        } catch (Exception e) {
            log.error("Gemini API call failed", e);
            throw new RuntimeException("Gemini API call failed: " + e.getMessage(), e);
        }
    }

    private String shorten(String value) {
        if (!StringUtils.hasText(value)) {
            return "(empty response)";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 400 ? normalized : normalized.substring(0, 400) + "...";
    }

    private String buildUrl() {
        String model = props.getGemini().getModel();
        if (model == null || model.isBlank()) model = "gemini-2.0-flash";
        String apiKey = props.getGemini().getApiKey();
        return "https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":generateContent?key=" + apiKey;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PROMPTS
    // ─────────────────────────────────────────────────────────────────────────

    private String buildImagePrompt(String targetLanguage) {
        return "You are a compassionate medical report assistant. "
                + "An image of a medical report is provided. "
                + "First, read ALL text in the image carefully. Then analyze it completely.\n\n"
                + buildJsonSchema(targetLanguage);
    }

    private String buildComprehensivePrompt(String reportText, String targetLanguage) {
        return "You are a compassionate medical report assistant helping patients understand their reports.\n\n"
                + "MEDICAL REPORT TEXT:\n---\n" + reportText + "\n---\n\n"
                + buildJsonSchema(targetLanguage);
    }

    // ✅ FIXED: replaced broken text block with String.format — this was causing all 42 errors
    private String buildJsonSchema(String targetLanguage) {
        String langName = getLanguageName(targetLanguage);

        return "Analyze this medical report and respond ONLY with valid JSON in this exact structure "
                + "(no markdown, no code fences, pure JSON only):\n\n"
                + "{\n"
                + "  \"extractedText\": \"Full text extracted from the report if image input, else empty string\",\n"
                + "  \"simpleSummary\": \"A warm, clear explanation in simple language. Explain what each test means, "
                + "which values are normal and which need attention. Mention patient name if visible.\",\n"
                + "  \"whatIsHappening\": \"In 2-3 sentences: what is going on in the patient body right now?\",\n"
                + "  \"whyDidThisHappen\": \"Common reasons why these results might be abnormal. "
                + "If all normal, explain why all is well.\",\n"
                + "  \"howToCure\": \"Practical advice: specific foods, lifestyle changes, "
                + "when to see a doctor, follow-up tests needed.\",\n"
                + "  \"keyHighlights\": \"Bullet points starting with bullet of the most important findings. "
                + "Mark abnormal with warning sign\",\n"
                + "  \"urgencyLevel\": \"LOW\",\n"
                + "  \"urgencyReason\": \"One sentence explaining the urgency level\",\n"
                + "  \"trustScore\": 0.85,\n"
                + "  \"trustReason\": \"Why this trust score was given\",\n"
                + "  \"disclaimer\": \"This is an AI-generated explanation. "
                + "Always consult your doctor for medical decisions.\",\n"
                + "  \"suggestedQuestions\": [\n"
                + "    \"What does this result mean for my daily life?\",\n"
                + "    \"Do I need any supplements?\",\n"
                + "    \"When should I repeat this test?\"\n"
                + "  ],\n"
                + "  \"findings\": [\n"
                + "    {\n"
                + "      \"parameterName\": \"Haemoglobin\",\n"
                + "      \"patientValue\": \"11.7\",\n"
                + "      \"unit\": \"gm/dl\",\n"
                + "      \"normalRange\": \"12 to 16\",\n"
                + "      \"status\": \"LOW\",\n"
                + "      \"aiInterpretation\": \"Your hemoglobin is slightly low. You may feel tired.\",\n"
                + "      \"layer\": \"LAB_RANGE\"\n"
                + "    }\n"
                + "  ],\n"
                + "  \"translatedSummary_" + targetLanguage + "\": "
                + "\"Translate simpleSummary into " + langName + ". If en repeat English.\",\n"
                + "  \"translatedSummary_hi\": \"Hindi mein report ki vyakhya karen.\",\n"
                + "  \"translatedSummary_mr\": \"Marathi madhye report che spashtikarana.\",\n"
                + "  \"translatedSummary_ta\": \"Tamil il report ai vilakkavum.\",\n"
                + "  \"translatedSummary_te\": \"Telugu lo report nu vivariyu.\"\n"
                + "}\n\n"
                + "STRICT RULES:\n"
                + "- findings status must be one of: NORMAL / LOW / HIGH / ABNORMAL / UNKNOWN\n"
                + "- findings layer must be one of: LAB_RANGE / GENERAL_RANGE / DESCRIPTIVE_RULE\n"
                + "- urgencyLevel must be one of: LOW / MEDIUM / HIGH / CRITICAL\n"
                + "- trustScore must be a decimal number between 0.0 and 1.0\n"
                + "- Write simpleSummary, whatIsHappening, whyDidThisHappen, howToCure, urgencyReason, trustReason, disclaimer, and suggestedQuestions in "
                + langName + "\n"
                + "- Be warm and encouraging — patients are often scared\n"
                + "- Include at least 3 suggestedQuestions specific to THIS report\n"
                + "- Return ONLY pure JSON — no markdown, no triple backticks, nothing else\n";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RESPONSE PARSER
    // ─────────────────────────────────────────────────────────────────────────

    private AiReportAnalysisResult parseResponse(String originalText, String geminiJson, String targetLanguage) {
        try {
            String cleanJson = geminiJson.trim();
            if (cleanJson.startsWith("```")) {
                cleanJson = cleanJson.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
            }

            // Safety net: if JSON was truncated mid-stream, trim to last valid closing brace
            if (!cleanJson.endsWith("}")) {
                int lastBrace = cleanJson.lastIndexOf('}');
                if (lastBrace != -1) {
                    cleanJson = cleanJson.substring(0, lastBrace + 1);
                    log.warn("Gemini response was truncated — trimmed to last valid JSON object boundary");
                }
            }

            JsonNode root = objectMapper.readTree(cleanJson);

            SummaryResult summary = new SummaryResult();
            summary.setMainSummary(buildRichSummary(root, targetLanguage));
            summary.setKeyHighlights(root.path("keyHighlights").asText(""));
            summary.setDisclaimer(root.path("disclaimer").asText(
                    "This is an AI-assisted explanation. Please consult your doctor for medical decisions."));

            Map<String, String> translations = new LinkedHashMap<>();
            for (String lang : List.of("en", "hi", "mr", "ta", "te")) {
                String val = root.path("translatedSummary_" + lang).asText("");
                if (!val.isBlank()) translations.put(lang, val);
            }
            summary.setTranslatedSummaries(translations);

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

            List<ParsedFindingCandidate> parsedFindings = new ArrayList<>();
            if (findings.isArray()) {
                for (JsonNode f : findings) {
                    ParsedFindingCandidate c = new ParsedFindingCandidate();
                    c.setParameterName(f.path("parameterName").asText(""));
                    c.setPatientValue(f.path("patientValue").asText(""));
                    c.setUnit(f.path("unit").asText(""));
                    c.setPrintedRangeText(f.path("normalRange").asText(""));

                    try {
                        String val = f.path("patientValue").asText("").replaceAll("[^0-9.]", "");
                        if (!val.isBlank()) c.setNumericValue(new BigDecimal(val));
                    } catch (NumberFormatException ignored) {}

                    parsePrintedRange(c, f.path("normalRange").asText(""));
                    c.setDescriptiveValue(f.path("aiInterpretation").asText(""));
                    c.setRawLine(c.getParameterName() + ": " + c.getPatientValue() + " " + c.getUnit());
                    parsedFindings.add(c);
                }
            }

            String extractedText = root.path("extractedText").asText("");
            if (extractedText.isBlank()) extractedText = originalText;

            return new AiReportAnalysisResult(extractedText, parsedFindings, summary);

        } catch (Exception e) {
            log.error("Failed to parse Gemini JSON response", e);
            SummaryResult sr = new SummaryResult();
            sr.setMainSummary("The report was analysed but the response could not be fully parsed. "
                    + "This can happen with very detailed reports. Please try again.");
            sr.setDisclaimer("AI-assisted explanation. Consult your doctor for medical decisions.");
            return new AiReportAnalysisResult(originalText, List.of(), sr);
        }
    }

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

    private void appendField(StringBuilder sb, JsonNode root, String field, String prefix) {
        String val = root.path(field).asText("");
        if (!val.isBlank()) {
            sb.append(prefix).append(val).append("\n\n");
        }
    }

    private void parsePrintedRange(ParsedFindingCandidate c, String rangeText) {
        if (rangeText == null || rangeText.isBlank()) return;
        String[] parts = rangeText.split("(?i)\\s*to\\s*|\\s*[-]\\s*");
        if (parts.length == 2) {
            try {
                c.setPrintedRangeLow(new BigDecimal(parts[0].trim().replaceAll("[^0-9.]", "")));
                c.setPrintedRangeHigh(new BigDecimal(parts[1].trim().replaceAll("[^0-9.]", "")));
            } catch (NumberFormatException ignored) {}
        }
    }

    private String getLanguageName(String code) {
        if (code == null) return "English";
        switch (code) {
            case "hi": return "Hindi";
            case "mr": return "Marathi";
            case "ta": return "Tamil";
            case "te": return "Telugu";
            default:   return "English";
        }
    }
}

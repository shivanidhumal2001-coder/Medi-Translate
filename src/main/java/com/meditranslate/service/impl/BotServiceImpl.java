package com.meditranslate.service.impl;

import com.meditranslate.config.MediTranslateProperties;
import com.meditranslate.dto.BotReplyDto;
import com.meditranslate.entity.ChatMessage;
import com.meditranslate.entity.ChatSender;
import com.meditranslate.entity.ReportAnalysis;
import com.meditranslate.entity.UserAccount;
import com.meditranslate.repository.ChatMessageRepository;
import com.meditranslate.service.BotService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service("botServiceImpl")
public class BotServiceImpl implements BotService {

    private static final Logger log = LoggerFactory.getLogger(BotServiceImpl.class);

    private final MediTranslateProperties props;
    private final ChatMessageRepository chatRepo;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BotServiceImpl(MediTranslateProperties props, ChatMessageRepository chatRepo) {
        this.props = props;
        this.chatRepo = chatRepo;
    }

    @Override
    public BotReplyDto answerQuestion(ReportAnalysis report, String question, UserAccount user) {

        // ── 1. Build the report context (injected once as the first user turn) ──
        String reportContext = buildReportContext(report);

        // ── 2. Load previous chat history for this report ─────────────────────
        List<ChatMessage> history = chatRepo.findByReportIdOrderByCreatedAtAsc(report.getId());

        // ── 3. Build Gemini "contents" array (multi-turn conversation) ─────────
        List<Map<String, Object>> contents = new ArrayList<>();

        // First turn: inject the full report as context so Gemini always knows it
        contents.add(turn("user",
                "Here is the patient's medical report. Please read it carefully. "
                + "I will ask you questions about it.\n\n" + reportContext));
        contents.add(turn("model",
                "I have carefully read the patient's medical report. "
                + "I'm ready to answer any questions about it in simple, friendly language. "
                + "Please go ahead and ask!"));

        // Previous conversation turns (last 10 pairs to stay within token limits)
        int start = Math.max(0, history.size() - 20);
        for (int i = start; i < history.size(); i++) {
            ChatMessage msg = history.get(i);
            String role = msg.getSender() == ChatSender.USER ? "user" : "model";
            contents.add(turn(role, msg.getMessage()));
        }

        // Current question
        contents.add(turn("user", question));

        // ── 4. System instruction ──────────────────────────────────────────────
        String systemInstruction = """
            You are MediBot — a knowledgeable, warm, and practical medical assistant built into MediTranslate.
            The patient's medical report is provided as context. Use it to personalise every answer.

            YOUR PERSONALITY:
            - Warm, clear, practical — like a knowledgeable doctor friend
            - Never cold or robotic
            - Always reassuring but truthful

            WHAT YOU CAN ANSWER:
            1. Questions about the report — explain values, findings, urgency in simple language
            2. General health questions — diet, nutrition, lifestyle, symptoms, medicines, wellness
            3. Personalised advice — ALWAYS connect general advice to the patient's specific report findings
               Example: if they ask about hair fall diet and their report shows low TSH/iron, mention that connection

            RESPONSE FORMAT — always use rich markdown:
            - Use **bold** for key terms and food names
            - Use numbered lists (1. 2. 3.) for steps or categories
            - Use bullet points (* item) for food lists
            - Use headers (### Section) for long responses with multiple sections
            - Add relevant emojis to section headers (🥗 🍳 💊 ⚠️ ✅)
            - End with a "**You might also ask:**" section with 2-3 follow-up questions as bullets

            RULES:
            - Always personalise to the patient's report — mention their specific abnormal values when relevant
            - Be specific and practical — give real food names, quantities, timing where helpful
            - For Indian patients: suggest Indian food alternatives (dal, paneer, roti, etc.)
            - NEVER fabricate lab values — only state values that are in the report
            - For serious symptoms, always recommend seeing a doctor
            - Keep responses focused — don't pad unnecessarily
            """;

        // ── 5. Call Gemini ─────────────────────────────────────────────────────
        String answer = callGemini(systemInstruction, contents);

        // ── 6. Save this turn to DB ────────────────────────────────────────────
        try {
            ChatMessage userMsg = new ChatMessage();
            userMsg.setReport(report);
            if (user != null) userMsg.setUser(user);
            userMsg.setSender(ChatSender.USER);
            userMsg.setMessage(question);
            chatRepo.save(userMsg);

            ChatMessage botMsg = new ChatMessage();
            botMsg.setReport(report);
            if (user != null) botMsg.setUser(user);
            botMsg.setSender(ChatSender.BOT);
            botMsg.setMessage(answer);
            chatRepo.save(botMsg);
        } catch (Exception e) {
            log.warn("Failed to save chat history: {}", e.getMessage());
        }

        return new BotReplyDto(answer, user != null);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Map<String, Object> turn(String role, String text) {
        return Map.of(
                "role", role,
                "parts", List.of(Map.of("text", text))
        );
    }

    private String buildReportContext(ReportAnalysis report) {
        StringBuilder sb = new StringBuilder();
        sb.append("Report Title: ").append(report.getTitle()).append("\n");
        sb.append("Urgency Level: ").append(
                report.getUrgencyLevel() != null ? report.getUrgencyLevel() : "Not specified"
        ).append("\n");
        sb.append("Summary: ").append(
                stripHtml(report.getSimpleSummary())
        ).append("\n\n");

        if (report.getFindings() != null && !report.getFindings().isEmpty()) {
            sb.append("Detailed Test Results:\n");
            report.getFindings().forEach(f -> {
                String status = f.isAbnormal() ? "⚠ ABNORMAL" : "✓ Normal";
                sb.append("• ").append(f.getParameterName())
                  .append(": ").append(f.getPatientValue())
                  .append(" ").append(f.getUnit() != null ? f.getUnit() : "")
                  .append("  [Reference: ").append(
                          f.getNormalRangeText() != null ? f.getNormalRangeText() : "N/A")
                  .append("]  ").append(status).append("\n");
                if (f.getAiInterpretation() != null && !f.getAiInterpretation().isBlank()) {
                    sb.append("  → ").append(f.getAiInterpretation()).append("\n");
                }
            });
        }
        return sb.toString();
    }

    /** Strip HTML tags from summary before sending to Gemini */
    private String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]*>", " ")
                   .replaceAll("\\s{2,}", " ")
                   .trim();
    }

    private String callGemini(String systemInstruction, List<Map<String, Object>> contents) {
        try {
            String model  = props.getGemini().getModel() != null
                    ? props.getGemini().getModel() : "gemini-2.5-flash";
            String apiKey = props.getGemini().getApiKey();
            String url    = props.getGemini().getBaseUrl()
                    + "/" + model + ":generateContent?key=" + apiKey;

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("system_instruction",
                    Map.of("parts", List.of(Map.of("text", systemInstruction))));
            body.put("contents", contents);
            body.put("generationConfig", Map.of(
                    "temperature", 0.5,
                    "maxOutputTokens", 2048
            ));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            String requestJson = objectMapper.writeValueAsString(body);
            HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);

            JsonNode node = objectMapper.readTree(response.getBody());
            return node.path("candidates").get(0)
                       .path("content").path("parts").get(0)
                       .path("text").asText(
                           "I couldn't generate an answer. Please try again.");

        } catch (Exception e) {
            log.error("BotService Gemini call failed", e);
            return "I'm having trouble connecting right now. Please try again in a moment. "
                 + "For urgent concerns, please consult your doctor directly.";
        }
    }
}
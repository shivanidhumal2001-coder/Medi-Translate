package com.meditranslate.service.impl;

import com.meditranslate.dto.BotReplyDto;
import com.meditranslate.entity.ChatMessage;
import com.meditranslate.entity.ChatSender;
import com.meditranslate.entity.ReportAnalysis;
import com.meditranslate.entity.ReportFinding;
import com.meditranslate.entity.UserAccount;
import com.meditranslate.repository.ChatMessageRepository;
import com.meditranslate.service.BotService;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service("contextAwareBotService")
public class ContextAwareBotService implements BotService {

    private final ChatMessageRepository chatMessageRepository;

    public ContextAwareBotService(ChatMessageRepository chatMessageRepository) {
        this.chatMessageRepository = chatMessageRepository;
    }

    @Override
    @Transactional
    public BotReplyDto answerQuestion(ReportAnalysis report, String question, UserAccount user) {
        String answer = generateAnswer(report, question);
        boolean saved = false;

        if (user != null) {
            ChatMessage userMessage = new ChatMessage();
            userMessage.setUser(user);
            userMessage.setReport(report);
            userMessage.setSender(ChatSender.USER);
            userMessage.setMessage(question);
            chatMessageRepository.save(userMessage);

            ChatMessage botMessage = new ChatMessage();
            botMessage.setUser(user);
            botMessage.setReport(report);
            botMessage.setSender(ChatSender.BOT);
            botMessage.setMessage(answer);
            chatMessageRepository.save(botMessage);
            saved = true;
        }

        return new BotReplyDto(answer, saved);
    }

    private String generateAnswer(ReportAnalysis report, String question) {
        String normalizedQuestion = question.toLowerCase(Locale.ROOT);
        List<ReportFinding> findings = report.getFindings();

        for (ReportFinding finding : findings) {
            if (normalizedQuestion.contains(finding.getParameterName().toLowerCase(Locale.ROOT))) {
                return """
                        %s is recorded as %s %s.
                        Verification says: %s.
                        AI explanation says: %s.
                        """.formatted(
                        finding.getParameterName(),
                        finding.getPatientValue(),
                        finding.getUnit() == null ? "" : finding.getUnit(),
                        finding.getSystemInterpretation(),
                        finding.getAiInterpretation()
                ).trim();
            }
        }

        if (normalizedQuestion.contains("urgent") || normalizedQuestion.contains("serious")) {
            return "Current urgency flag: " + report.getUrgencyLevel()
                    + ". Please use this as a guidance signal only and confirm severity with a doctor, especially if symptoms are active.";
        }

        if (normalizedQuestion.contains("summary") || normalizedQuestion.contains("overall")) {
            return report.getSimpleSummary();
        }

        if (normalizedQuestion.contains("doctor") || normalizedQuestion.contains("next step")) {
            return """
                    Good next steps:
                    1. Show the report and this simplified summary to your doctor.
                    2. Discuss the abnormal findings first.
                    3. Mention current symptoms, medicines, and past conditions.
                    """.trim();
        }

        List<ReportFinding> abnormalFindings = findings.stream().filter(ReportFinding::isAbnormal).toList();
        if (!abnormalFindings.isEmpty()) {
            String abnormalNames = abnormalFindings.stream()
                    .limit(4)
                    .map(ReportFinding::getParameterName)
                    .collect(Collectors.joining(", "));
            return "The main report concerns look related to: " + abnormalNames
                    + ". Ask me about any one of these values and I can explain it in simpler language.";
        }

        return "The extracted findings look mostly stable in the available ranges. You can ask me about a specific value, symptom, medicine timing, or what the trust score means.";
    }
}
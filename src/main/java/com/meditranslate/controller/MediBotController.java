package com.meditranslate.controller;

import com.meditranslate.dto.BotReplyDto;
import com.meditranslate.dto.ChatRequest;
import com.meditranslate.dto.ReminderRequest;
import com.meditranslate.dto.SymptomInsightDto;
import com.meditranslate.dto.SymptomRequest;
import com.meditranslate.entity.ReportAnalysis;
import com.meditranslate.entity.UserAccount;
import com.meditranslate.service.BotService;
import com.meditranslate.service.GuestSessionService;
import com.meditranslate.service.MedicationReminderService;
import com.meditranslate.service.ReportAnalysisService;
import com.meditranslate.service.SymptomMatcherService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bot")
public class MediBotController {

    private final ReportAnalysisService reportAnalysisService;
    private final GuestSessionService guestSessionService;
    private final BotService botService;
    private final SymptomMatcherService symptomMatcherService;
    private final MedicationReminderService medicationReminderService;

    public MediBotController(ReportAnalysisService reportAnalysisService,
                             GuestSessionService guestSessionService,
                             @Qualifier("botServiceImpl") BotService botService,
                             SymptomMatcherService symptomMatcherService,
                             MedicationReminderService medicationReminderService) {
        this.reportAnalysisService = reportAnalysisService;
        this.guestSessionService = guestSessionService;
        this.botService = botService;
        this.symptomMatcherService = symptomMatcherService;
        this.medicationReminderService = medicationReminderService;
    }

    @PostMapping("/{reportId}/ask")
    public Map<String, Object> ask(@PathVariable Long reportId,
                                   @Valid @RequestBody ChatRequest request,
                                   @AuthenticationPrincipal UserAccount user,
                                   HttpSession session) {
        ReportAnalysis report = resolveReport(reportId, user, session);
        BotReplyDto reply = botService.answerQuestion(report, request.getQuestion(), user);
        return Map.of(
                "answer", reply.getAnswer(),
                "savedToHistory", reply.isSavedToHistory()
        );
    }

    @PostMapping("/{reportId}/symptoms")
    public List<SymptomInsightDto> checkSymptoms(@PathVariable Long reportId,
                                                 @Valid @RequestBody SymptomRequest request,
                                                 @AuthenticationPrincipal UserAccount user,
                                                 HttpSession session) {
        ReportAnalysis report = resolveReport(reportId, user, session);
        return symptomMatcherService.matchSymptoms(report, request.getSymptoms());
    }

    @PostMapping("/{reportId}/reminders")
    public Object generateReminders(@PathVariable Long reportId,
                                    @Valid @RequestBody ReminderRequest request,
                                    @AuthenticationPrincipal UserAccount user,
                                    HttpSession session) {
        ReportAnalysis report = resolveReport(reportId, user, session);
        return medicationReminderService.generateReminders(report, request.getPrescriptionText());
    }

    private ReportAnalysis resolveReport(Long reportId, UserAccount user, HttpSession session) {
        String guestToken = guestSessionService.getOrCreateSessionToken(session);
        return reportAnalysisService.findAccessibleReport(reportId, user, guestToken);
    }
}
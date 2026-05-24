package com.meditranslate.controller;

import com.meditranslate.entity.ReportAnalysis;
import com.meditranslate.entity.UserAccount;
import com.meditranslate.service.GuestSessionService;
import com.meditranslate.service.PdfExportService;
import com.meditranslate.service.ReportAnalysisService;
import com.meditranslate.service.SummarySpeechService;
import com.meditranslate.dto.SummarySpeechRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

@Controller
public class ReportController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReportController.class);

    private final ReportAnalysisService reportAnalysisService;
    private final GuestSessionService guestSessionService;
    private final PdfExportService pdfExportService;
    private final SummarySpeechService summarySpeechService;

    public ReportController(ReportAnalysisService reportAnalysisService,
                            GuestSessionService guestSessionService,
                            PdfExportService pdfExportService,
                            SummarySpeechService summarySpeechService) {
        this.reportAnalysisService = reportAnalysisService;
        this.guestSessionService = guestSessionService;
        this.pdfExportService = pdfExportService;
        this.summarySpeechService = summarySpeechService;
    }

    @GetMapping("/reports/new")
    public String newReportForm(@AuthenticationPrincipal UserAccount user, Model model) {
        model.addAttribute("currentUser", user);
        return "report/form";
    }

    @PostMapping("/reports/analyze")
    public String analyzeReport(@RequestParam(required = false) String title,
                                @RequestParam(required = false) String typedText,
                                @RequestParam(required = false) MultipartFile reportFile,
                                @RequestParam(defaultValue = "en") String targetLanguage,
                                @AuthenticationPrincipal UserAccount user,
                                HttpSession session,
                                Model model) {
        try {
            String guestToken = guestSessionService.getOrCreateSessionToken(session);
            ReportAnalysis report = reportAnalysisService.createAnalysis(
                    title,
                    typedText,
                    reportFile,
                    targetLanguage,
                    user,
                    guestToken
            );
            return "redirect:/reports/" + report.getId();
        } catch (IllegalArgumentException ex) {
            LOGGER.warn("Report analysis validation failed: {}", ex.getMessage());
            populateUploadState(model, typedText, title, targetLanguage, user, ex.getMessage());
            return "report/form";
        } catch (Exception ex) {
            LOGGER.error("Unexpected error while analyzing report", ex);
            populateUploadState(
                    model,
                    typedText,
                    title,
                    targetLanguage,
                    user,
                    "We couldn't analyze that file right now. If it is an image, please install Tesseract OCR or paste the report text directly."
            );
            return "report/form";
        }
    }

    private void populateUploadState(Model model, String typedText, String title, String targetLanguage,
                                     UserAccount user, String uploadError) {
        model.addAttribute("uploadError", uploadError);
        model.addAttribute("typedText", typedText);
        model.addAttribute("title", title);
        model.addAttribute("targetLanguage", targetLanguage);
        model.addAttribute("currentUser", user);
    }

    @GetMapping("/reports/{reportId}")
    public String reportDetail(@PathVariable Long reportId,
                               @AuthenticationPrincipal UserAccount user,
                               HttpSession session,
                               Model model) {
        String guestToken = guestSessionService.getOrCreateSessionToken(session);
        ReportAnalysis report = reportAnalysisService.findAccessibleReport(reportId, user, guestToken);

        model.addAttribute("currentUser", user);
        model.addAttribute("report", report);
        model.addAttribute("reportHighlights", splitHighlights(report.getKeyHighlights()));
        model.addAttribute("trustScorePercent", report.getTrustScore() == null ? 0 : Math.round(report.getTrustScore() * 100));
        return "report/result";
    }

    @GetMapping("/reports/{reportId}/pdf")
    public ResponseEntity<ByteArrayResource> exportPdf(@PathVariable Long reportId,
                                                       @AuthenticationPrincipal UserAccount user,
                                                       HttpSession session) {
        String guestToken = guestSessionService.getOrCreateSessionToken(session);
        ReportAnalysis report = reportAnalysisService.findAccessibleReport(reportId, user, guestToken);
        byte[] pdf = pdfExportService.exportReport(report);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"medi-translate-report-" + reportId + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.length)
                .body(new ByteArrayResource(pdf));
    }

    @GetMapping(value = "/reports/{reportId}/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, String> translatedSummary(@PathVariable Long reportId,
                                                 @RequestParam String language,
                                                 @AuthenticationPrincipal UserAccount user,
                                                 HttpSession session) {
        String guestToken = guestSessionService.getOrCreateSessionToken(session);
        ReportAnalysis report = reportAnalysisService.findAccessibleReport(reportId, user, guestToken);
        String summaryHtml = reportAnalysisService.resolveSummaryHtml(report, language);
        return Map.of("summaryHtml", summaryHtml == null ? "" : summaryHtml);
    }

    @PostMapping(value = "/reports/{reportId}/summary/audio",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = "audio/wav")
    @ResponseBody
    public ResponseEntity<byte[]> summaryAudio(@PathVariable Long reportId,
                                               @Valid @RequestBody SummarySpeechRequest request,
                                               @AuthenticationPrincipal UserAccount user,
                                               HttpSession session) {
        String guestToken = guestSessionService.getOrCreateSessionToken(session);
        reportAnalysisService.findAccessibleReport(reportId, user, guestToken);

        if (!summarySpeechService.isAvailable()) {
            return ResponseEntity.status(503).build();
        }
        if (!StringUtils.hasText(request.getText())) {
            return ResponseEntity.badRequest().build();
        }

        byte[] wavAudio = summarySpeechService.synthesizeSummaryAudio(
                request.getText(),
                request.getLanguage(),
                request.getVoice()
        );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType("audio/wav"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"report-summary.wav\"")
                .body(wavAudio);
    }

    private List<String> splitHighlights(String rawHighlights) {
        if (rawHighlights == null || rawHighlights.isBlank()) {
            return List.of();
        }
        return Arrays.stream(rawHighlights.split("\\R+"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
    }
}

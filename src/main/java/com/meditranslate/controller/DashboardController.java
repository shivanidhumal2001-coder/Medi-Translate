package com.meditranslate.controller;

import com.meditranslate.entity.ReportAnalysis;
import com.meditranslate.entity.UserAccount;
import com.meditranslate.service.ReportAnalysisService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

    private final ReportAnalysisService reportAnalysisService;

    public DashboardController(ReportAnalysisService reportAnalysisService) {
        this.reportAnalysisService = reportAnalysisService;
    }

    @GetMapping("/dashboard")
    public String dashboard(@AuthenticationPrincipal UserAccount user, Model model) {
        List<ReportAnalysis> reports = reportAnalysisService.findDashboardReports(user);
        long urgentReports = reports.stream().filter(report -> report.getUrgencyLevel().name().equals("HIGH")).count();
        double avgTrust = reports.stream().mapToDouble(ReportAnalysis::getTrustScore).average().orElse(0);
        Map<Long, String> reportPreviewSummaries = new LinkedHashMap<>();
        Map<Long, Long> reportTrustPercents = new LinkedHashMap<>();

        for (ReportAnalysis report : reports) {
            reportPreviewSummaries.put(report.getId(), buildSummaryPreview(report.getSimpleSummary()));
            reportTrustPercents.put(report.getId(), toPercent(report.getTrustScore()));
        }

        model.addAttribute("currentUser", user);
        model.addAttribute("reports", reports);
        model.addAttribute("urgentReports", urgentReports);
        model.addAttribute("avgTrustPercent", Math.round(avgTrust * 1000.0) / 10.0);
        model.addAttribute("reportPreviewSummaries", reportPreviewSummaries);
        model.addAttribute("reportTrustPercents", reportTrustPercents);
        return "dashboard/home";
    }

    private String buildSummaryPreview(String summaryHtml) {
        if (summaryHtml == null || summaryHtml.isBlank()) {
            return "No summary available yet.";
        }

        String plainText = summaryHtml
                .replaceAll("(?i)<br\\s*/?>", " ")
                .replaceAll("(?i)</p>", " ")
                .replaceAll("(?i)</li>", " ")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");

        plainText = plainText.replaceAll("\\s+", " ").trim();
        if (plainText.length() <= 260) {
            return plainText;
        }
        return plainText.substring(0, 257) + "...";
    }

    private long toPercent(Double trustScore) {
        if (trustScore == null) {
            return 0;
        }
        return Math.round(trustScore * 100);
    }
}

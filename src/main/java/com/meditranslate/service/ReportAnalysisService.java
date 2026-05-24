package com.meditranslate.service;

import com.meditranslate.entity.ReportAnalysis;
import com.meditranslate.entity.UserAccount;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface ReportAnalysisService {

    ReportAnalysis createAnalysis(String title,
                                  String typedText,
                                  MultipartFile reportFile,
                                  String targetLanguage,
                                  UserAccount user,
                                  String guestToken);

    ReportAnalysis findAccessibleReport(Long reportId, UserAccount user, String guestToken);

    // ✅ FIXED: Added — required by DashboardController
    List<ReportAnalysis> findDashboardReports(UserAccount user);

    String resolveSummaryHtml(ReportAnalysis report, String targetLanguage);
}

package com.meditranslate.service.impl;

import com.meditranslate.config.MediTranslateProperties;
import com.meditranslate.dto.AiReportAnalysisResult;
import com.meditranslate.dto.ParsedFindingCandidate;
import com.meditranslate.dto.SummaryResult;
import com.meditranslate.entity.*;
import com.meditranslate.repository.ReportAnalysisRepository;
import com.meditranslate.service.AiAnalysisService;
import com.meditranslate.service.ReportAnalysisService;
import com.meditranslate.util.RichSummaryHtmlBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

@Service
public class ReportAnalysisServiceImpl implements ReportAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ReportAnalysisServiceImpl.class);

    private static final Set<String> IMAGE_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp", "image/bmp"
    );
    private static final int MAX_TEXT_LENGTH = 80_000;

    private final AiAnalysisService aiAnalysisService;
    private final ImageExtractionService imageExtractionService;
    private final ReportAnalysisRepository reportRepo;
    private final MediTranslateProperties props;

    public ReportAnalysisServiceImpl(AiAnalysisService aiAnalysisService,
                                     ImageExtractionService imageExtractionService,
                                     ReportAnalysisRepository reportRepo,
                                     MediTranslateProperties props) {
        this.aiAnalysisService = aiAnalysisService;
        this.imageExtractionService = imageExtractionService;
        this.reportRepo = reportRepo;
        this.props = props;
    }

    @Override
    public ReportAnalysis createAnalysis(String title,
                                         String typedText,
                                         MultipartFile reportFile,
                                         String targetLanguage,
                                         UserAccount user,
                                         String guestToken) {

        boolean hasText = StringUtils.hasText(typedText);
        boolean hasFile = reportFile != null && !reportFile.isEmpty();
        if (!hasText && !hasFile) {
            throw new IllegalArgumentException("Please provide either report text or upload a file.");
        }

        String safeTitle = StringUtils.hasText(title) ? title.trim() : "Medical Report";
        String safeLanguage = StringUtils.hasText(targetLanguage) ? targetLanguage : "en";

        try {
            AiReportAnalysisResult analysisResult;
            ReportSourceType sourceType;
            String extractedText;
            String originalFileName = null;
            String storedFilePath = null;

            if (hasFile) {
                String mimeType = detectMimeType(reportFile);
                byte[] fileBytes = reportFile.getBytes();
                originalFileName = reportFile.getOriginalFilename();
                storedFilePath = saveFile(reportFile);

                if (IMAGE_CONTENT_TYPES.contains(mimeType)) {
                    log.info("Analyzing image via configured AI vision service: {}", originalFileName);
                    analysisResult = aiAnalysisService.analyzeImage(fileBytes, mimeType, safeLanguage);
                    sourceType = ReportSourceType.IMAGE_UPLOAD;   // ✅ FIXED

                } else if ("application/pdf".equals(mimeType)) {
                    log.info("Analyzing PDF via extraction pipeline: {}", originalFileName);
                    analysisResult = imageExtractionService.extractAndAnalyze(reportFile, safeLanguage);
                    sourceType = ReportSourceType.DOCUMENT_UPLOAD; // ✅ FIXED

                } else {
                    String textFromFile = new String(fileBytes).trim();
                    analysisResult = aiAnalysisService.analyzeText(textFromFile, safeLanguage);
                    sourceType = ReportSourceType.TEXT_INPUT;      // ✅ FIXED
                }
                extractedText = getExtractedText(analysisResult, "");

            } else {
                String cleanText = typedText.trim();
                if (cleanText.length() > MAX_TEXT_LENGTH) {
                    cleanText = cleanText.substring(0, MAX_TEXT_LENGTH);
                }
                log.info("Analyzing typed text ({} chars)", cleanText.length());
                analysisResult = aiAnalysisService.analyzeText(cleanText, safeLanguage);
                sourceType = ReportSourceType.TEXT_INPUT;          // ✅ FIXED
                extractedText = cleanText;
            }

            ReportAnalysis report = buildReportEntity(
                    safeTitle, safeLanguage, typedText, extractedText,
                    sourceType, originalFileName, storedFilePath,
                    analysisResult, user, guestToken
            );

            List<ReportFinding> findings = buildFindings(report, analysisResult);
            findings.forEach(report::addFinding);

            report.setTrustScore(extractTrustScore(analysisResult));
            report.setUrgencyLevel(extractUrgencyLevel(analysisResult));

            return reportRepo.save(report);

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Analysis failed", e);
            throw new RuntimeException("Report analysis failed: " + e.getMessage(), e);
        }
    }

    @Override
    public ReportAnalysis findAccessibleReport(Long reportId, UserAccount user, String guestToken) {
        ReportAnalysis report = reportRepo.findById(reportId)
                .orElseThrow(() -> new NoSuchElementException("Report not found: " + reportId));

        boolean isOwner = user != null && report.isOwnedBy(user);
        boolean isGuest = guestToken != null && guestToken.equals(report.getGuestSessionId());
        if (!isOwner && !isGuest) {
            throw new SecurityException("Access denied to report: " + reportId);
        }
        return report;
    }

    // ✅ FIXED: Added findDashboardReports — required by DashboardController
    @Override
    public List<ReportAnalysis> findDashboardReports(UserAccount user) {
        if (user == null) return List.of();
        return reportRepo.findByUserIdOrderByCreatedAtDesc(user.getId());
    }

    @Override
    public String resolveSummaryHtml(ReportAnalysis report, String targetLanguage) {
        String safeLanguage = StringUtils.hasText(targetLanguage) ? targetLanguage.trim() : report.getSummaryLanguage();
        if (!StringUtils.hasText(safeLanguage) || safeLanguage.equals(report.getSummaryLanguage())) {
            return report.getSimpleSummary();
        }

        if (report.getMultilingualSummaries() == null) {
            report.setMultilingualSummaries(new LinkedHashMap<>());
        }

        String existing = report.getMultilingualSummaries().get(safeLanguage);
        if (RichSummaryHtmlBuilder.looksLikeRichHtml(existing)) {
            return existing;
        }

        String sourceText = StringUtils.hasText(report.getExtractedText())
                ? report.getExtractedText()
                : report.getOriginalText();
        if (!StringUtils.hasText(sourceText)) {
            return StringUtils.hasText(existing) ? existing : report.getSimpleSummary();
        }

        AiReportAnalysisResult translatedAnalysis = aiAnalysisService.analyzeText(sourceText, safeLanguage);
        if (!translatedAnalysis.hasSummary()) {
            return StringUtils.hasText(existing) ? existing : report.getSimpleSummary();
        }

        String translatedSummary = translatedAnalysis.getSummaryResult().getMainSummary();
        if (!StringUtils.hasText(translatedSummary)) {
            return StringUtils.hasText(existing) ? existing : report.getSimpleSummary();
        }

        report.getMultilingualSummaries().put(safeLanguage, translatedSummary);
        reportRepo.save(report);
        return translatedSummary;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private ReportAnalysis buildReportEntity(String title, String language,
                                              String originalText, String extractedText,
                                              ReportSourceType sourceType,
                                              String originalFileName, String storedFilePath,
                                              AiReportAnalysisResult ai,
                                              UserAccount user, String guestToken) {
        ReportAnalysis report = new ReportAnalysis();
        report.setTitle(title);
        report.setSummaryLanguage(language);
        report.setSourceType(sourceType);
        report.setOriginalText(originalText);
        report.setExtractedText(extractedText);
        report.setOriginalFileName(originalFileName);
        report.setStoredFilePath(storedFilePath);
        report.setUser(user);
        report.setGuestSessionId(guestToken);

        if (ai.hasSummary()) {
            SummaryResult sr = ai.getSummaryResult();
            report.setSimpleSummary(sr.getMainSummary());
            report.setKeyHighlights(sr.getKeyHighlights());
            report.setDisclaimer(sr.getDisclaimer());
            if (sr.getTranslatedSummaries() != null) {
                report.setMultilingualSummaries(sr.getTranslatedSummaries());
            }
        }
        return report;
    }

    private List<ReportFinding> buildFindings(ReportAnalysis report, AiReportAnalysisResult ai) {
        if (!ai.hasParsedFindings()) return List.of();
        return ai.getParsedFindings().stream()
                .map(candidate -> buildFinding(report, candidate))
                .toList();
    }

 // ── COPY THIS ENTIRE METHOD into ReportAnalysisServiceImpl.java ──
 // Replace your existing buildFinding() method with this one.

 private ReportFinding buildFinding(ReportAnalysis report, ParsedFindingCandidate c) {
     ReportFinding f = new ReportFinding();
     f.setReport(report);
     f.setParameterName(truncate(c.getParameterName(), 180));
     f.setPatientValue(truncate(c.getPatientValue(), 80));
     f.setUnit(truncate(c.getUnit(), 40));
     f.setNormalRangeText(truncate(c.getPrintedRangeText(), 120));
     f.setRangeLow(c.getPrintedRangeLow());
     f.setRangeHigh(c.getPrintedRangeHigh());
     f.setRawLine(truncate(c.getRawLine(), 500));
     f.setAiInterpretation(truncate(c.getDescriptiveValue(), 160));
     f.setMatched(c.hasPrintedRange() || c.hasNumericValue());
     f.setVerificationLayer(c.hasPrintedRange()
             ? VerificationLayer.LAB_RANGE : VerificationLayer.DESCRIPTIVE_RULE);

     // ── Determine status from numeric comparison ───────────────
     FindingStatus status = FindingStatus.UNKNOWN;
     if (c.hasNumericValue() && c.hasPrintedRange()) {
         int cmpLow  = c.getNumericValue().compareTo(c.getPrintedRangeLow());
         int cmpHigh = c.getNumericValue().compareTo(c.getPrintedRangeHigh());
         if (cmpLow >= 0 && cmpHigh <= 0) {
             status = FindingStatus.NORMAL;
         } else if (cmpLow < 0) {
             status = FindingStatus.LOW;
         } else {
             status = FindingStatus.HIGH;
         }
     }
     f.setStatus(status);
     f.setAbnormal(status == FindingStatus.LOW
             || status == FindingStatus.HIGH
             || status == FindingStatus.ABNORMAL);
     f.setEvidenceSource(c.hasPrintedRange() ? "Printed lab range" : "AI interpretation");

     // ✅ FIX: Set systemInterpretation so the column is not blank
     f.setSystemInterpretation(buildSystemInterpretation(status, c));

     return f;
 }

 // ✅ NEW METHOD — add this below buildFinding()
 private String buildSystemInterpretation(FindingStatus status, ParsedFindingCandidate c) {
     switch (status) {
         case NORMAL:
             return "✅ Within normal range";
         case LOW:
             String lowRange = c.getPrintedRangeLow() != null
                     ? " (min: " + c.getPrintedRangeLow().stripTrailingZeros().toPlainString() + ")" : "";
             return "⬇️ Below normal" + lowRange;
         case HIGH:
             String highRange = c.getPrintedRangeHigh() != null
                     ? " (max: " + c.getPrintedRangeHigh().stripTrailingZeros().toPlainString() + ")" : "";
             return "⬆️ Above normal" + highRange;
         case ABNORMAL:
             return "⚠️ Abnormal result";
         case POSITIVE:
             return "🔴 Positive";
         case NEGATIVE:
             return "🟢 Negative";
         default:
             // No numeric range to compare — use AI interpretation as system note
             return c.hasPrintedRange() ? "📋 Range checked" : "📝 Descriptive only";
     }
 }
 
 

    private double extractTrustScore(AiReportAnalysisResult ai) {
        if (!ai.hasParsedFindings()) return 0.60;
        long withRange = ai.getParsedFindings().stream()
                .filter(ParsedFindingCandidate::hasPrintedRange).count();
        return Math.min(0.95, 0.60 + (withRange * 0.05));
    }

    private UrgencyLevel extractUrgencyLevel(AiReportAnalysisResult ai) {
        if (!ai.hasParsedFindings()) return UrgencyLevel.MEDIUM;

        boolean hasCritical = ai.getParsedFindings().stream()
                .anyMatch(f -> {
                    if (!f.hasNumericValue() || !f.hasPrintedRange()) return false;
                    var val = f.getNumericValue();
                    var lo  = f.getPrintedRangeLow();
                    var hi  = f.getPrintedRangeHigh();
                    return val.compareTo(hi.multiply(java.math.BigDecimal.valueOf(2))) > 0
                            || (lo.compareTo(java.math.BigDecimal.ZERO) > 0
                                && val.compareTo(lo.multiply(java.math.BigDecimal.valueOf(0.5))) < 0);
                });
        if (hasCritical) return UrgencyLevel.HIGH;

        long abnormal = ai.getParsedFindings().stream()
                .filter(f -> f.hasNumericValue() && f.hasPrintedRange())
                .filter(f -> f.getNumericValue().compareTo(f.getPrintedRangeLow()) < 0
                          || f.getNumericValue().compareTo(f.getPrintedRangeHigh()) > 0)
                .count();

        if (abnormal == 0) return UrgencyLevel.LOW;
        if (abnormal <= 2) return UrgencyLevel.MEDIUM;
        return UrgencyLevel.HIGH;
    }

    private String getExtractedText(AiReportAnalysisResult ai, String fallback) {
        String text = ai.getExtractedReportText();
        return (text != null && !text.isBlank()) ? text : fallback;
    }

    private String detectMimeType(MultipartFile file) {
        String ct = file.getContentType();
        if (ct != null) return ct.toLowerCase();
        String name = file.getOriginalFilename();
        if (name == null) return "application/octet-stream";
        String lower = name.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".gif"))  return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".pdf"))  return "application/pdf";
        return "text/plain";
    }

    private String saveFile(MultipartFile file) {
        try {
            java.nio.file.Path uploadPath = java.nio.file.Paths.get(props.getStoragePath());
            if (!java.nio.file.Files.exists(uploadPath)) {
                java.nio.file.Files.createDirectories(uploadPath);
            }
            String fileName = java.util.UUID.randomUUID() + "_" + file.getOriginalFilename();
            java.nio.file.Path target = uploadPath.resolve(fileName);
            java.nio.file.Files.copy(file.getInputStream(), target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return target.toString();
        } catch (Exception e) {
            log.warn("Failed to save uploaded file: {}", e.getMessage());
            return null;
        }
    }

    private String truncate(String value, int maxLen) {
        if (value == null) return null;
        return value.length() <= maxLen ? value : value.substring(0, maxLen - 1);
    }
}

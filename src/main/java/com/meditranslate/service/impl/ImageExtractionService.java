package com.meditranslate.service.impl;

import com.meditranslate.dto.AiReportAnalysisResult;
import com.meditranslate.entity.ReportSourceType;
import com.meditranslate.service.AiAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Set;

/**
 * ImageExtractionService — replaces Tesseract OCR with Claude Vision AI.
 * Claude reads images directly — no Tesseract installation required.
 */
@Service
public class ImageExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ImageExtractionService.class);

    private static final Set<String> IMAGE_MIME_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp", "image/bmp"
    );

    private final AiAnalysisService aiAnalysisService;

    public ImageExtractionService(AiAnalysisService aiAnalysisService) {
        this.aiAnalysisService = aiAnalysisService;
    }

    /**
     * Extracts text AND performs full analysis from an uploaded file.
     * Images → Claude Vision; PDFs → Tika; anything else → text.
     */
    public AiReportAnalysisResult extractAndAnalyze(MultipartFile file, String targetLanguage) throws IOException {
        byte[] fileBytes = file.getBytes();
        String mimeType = detectMimeType(file);

        log.info("Processing file: name={}, type={}, size={} bytes",
                file.getOriginalFilename(), mimeType, fileBytes.length);

        if (IMAGE_MIME_TYPES.contains(mimeType)) {
            // ✅ Claude Vision — no Tesseract needed
            log.info("Sending image to Claude Vision API");
            return aiAnalysisService.analyzeImage(fileBytes, mimeType, targetLanguage);

        } else if ("application/pdf".equals(mimeType)) {
            String pdfText = extractPdfText(fileBytes);
            if (pdfText != null && pdfText.length() > 50) {
                log.info("PDF text extracted ({} chars), sending to Claude", pdfText.length());
                return aiAnalysisService.analyzeText(pdfText, targetLanguage);
            } else {
                // Scanned PDF — treat as image
                log.info("PDF appears scanned, using Claude Vision");
                return aiAnalysisService.analyzeImage(fileBytes, "application/pdf", targetLanguage);
            }
        } else {
            String rawText = new String(fileBytes);
            return aiAnalysisService.analyzeText(rawText, targetLanguage);
        }
    }

    // ✅ FIXED: Use IMAGE_UPLOAD / DOCUMENT_UPLOAD / TEXT_INPUT
    public ReportSourceType resolveSourceType(MultipartFile file) {
        String mime = detectMimeType(file);
        if (IMAGE_MIME_TYPES.contains(mime)) return ReportSourceType.IMAGE_UPLOAD;
        if ("application/pdf".equals(mime))  return ReportSourceType.DOCUMENT_UPLOAD;
        return ReportSourceType.TEXT_INPUT;
    }

    private String detectMimeType(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType != null && !contentType.isBlank()) return contentType.toLowerCase();

        String name = file.getOriginalFilename();
        if (name != null) {
            String lower = name.toLowerCase();
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            if (lower.endsWith(".png"))  return "image/png";
            if (lower.endsWith(".gif"))  return "image/gif";
            if (lower.endsWith(".webp")) return "image/webp";
            if (lower.endsWith(".pdf"))  return "application/pdf";
        }
        return "application/octet-stream";
    }

    private String extractPdfText(byte[] pdfBytes) {
        try {
            org.apache.tika.Tika tika = new org.apache.tika.Tika();
            return tika.parseToString(new java.io.ByteArrayInputStream(pdfBytes));
        } catch (Exception e) {
            log.warn("Tika PDF extraction failed: {}", e.getMessage());
            return null;
        }
    }
}
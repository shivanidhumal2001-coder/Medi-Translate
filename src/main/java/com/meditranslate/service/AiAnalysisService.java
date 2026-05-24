package com.meditranslate.service;

import com.meditranslate.dto.AiReportAnalysisResult;

/**
 * Contract for AI-powered medical report analysis.
 * Implemented by ClaudeAiService (and optionally GeminiAiService).
 */
public interface AiAnalysisService {

    /**
     * Analyze a text-based medical report.
     * @param reportText    the full text of the medical report
     * @param targetLanguage ISO language code e.g. "en", "hi", "mr"
     * @return structured analysis result
     */
    AiReportAnalysisResult analyzeText(String reportText, String targetLanguage);

    /**
     * Analyze a medical report image using AI Vision (no Tesseract needed).
     * @param imageBytes     raw image bytes (JPEG, PNG, etc.)
     * @param mimeType       e.g. "image/jpeg", "image/png"
     * @param targetLanguage ISO language code
     * @return structured analysis result with extracted text + full analysis
     */
    AiReportAnalysisResult analyzeImage(byte[] imageBytes, String mimeType, String targetLanguage);

    /**
     * Extract only the text from a medical report image (OCR via Vision AI).
     */
    String extractTextFromImage(byte[] imageBytes, String mimeType);

    /**
     * Returns true if this AI service has a configured API key.
     */
    boolean isAvailable();
}
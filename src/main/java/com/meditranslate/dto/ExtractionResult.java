package com.meditranslate.dto;

import com.meditranslate.entity.ReportSourceType;

public class ExtractionResult {

    private final String originalText;
    private final String extractedText;
    private final ReportSourceType sourceType;
    private final String originalFileName;
    private final String storedFilePath;

    public ExtractionResult(String originalText, String extractedText, ReportSourceType sourceType,
                            String originalFileName, String storedFilePath) {
        this.originalText = originalText;
        this.extractedText = extractedText;
        this.sourceType = sourceType;
        this.originalFileName = originalFileName;
        this.storedFilePath = storedFilePath;
    }

    public String getOriginalText() {
        return originalText;
    }

    public String getExtractedText() {
        return extractedText;
    }

    public ReportSourceType getSourceType() {
        return sourceType;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public String getStoredFilePath() {
        return storedFilePath;
    }
}

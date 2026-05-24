package com.meditranslate.service;

import com.meditranslate.dto.AiReportAnalysisResult;
import com.meditranslate.dto.ExtractionResult;
import java.util.Optional;

public interface AiReportAnalysisService {
    Optional<AiReportAnalysisResult> analyze(ExtractionResult extractionResult, String targetLanguage);
}

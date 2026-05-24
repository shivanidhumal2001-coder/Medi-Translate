package com.meditranslate.service;

import com.meditranslate.dto.ParsedFindingCandidate;
import com.meditranslate.dto.SummaryResult;
import java.util.List;

public interface ClinicalSummaryService {
    SummaryResult summarize(String extractedText, List<ParsedFindingCandidate> findings, String targetLanguage);
}

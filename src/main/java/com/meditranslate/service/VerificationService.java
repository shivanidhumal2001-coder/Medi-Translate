package com.meditranslate.service;

import com.meditranslate.dto.ParsedFindingCandidate;
import com.meditranslate.dto.SummaryResult;
import com.meditranslate.dto.VerificationResult;
import java.util.List;

public interface VerificationService {
    VerificationResult verify(String extractedText, List<ParsedFindingCandidate> parsedFindings, SummaryResult summaryResult);
}

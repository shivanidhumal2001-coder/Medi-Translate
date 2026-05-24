package com.meditranslate.service;

import com.meditranslate.dto.ParsedFindingCandidate;
import java.util.List;

public interface FindingParserService {
    List<ParsedFindingCandidate> parseFindings(String extractedText);
}

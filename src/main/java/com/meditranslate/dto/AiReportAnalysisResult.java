package com.meditranslate.dto;

import java.util.List;
import org.springframework.util.StringUtils;

public class AiReportAnalysisResult {

    private final String extractedReportText;
    private final List<ParsedFindingCandidate> parsedFindings;
    private final SummaryResult summaryResult;

    public AiReportAnalysisResult(String extractedReportText, List<ParsedFindingCandidate> parsedFindings,
                                  SummaryResult summaryResult) {
        this.extractedReportText = extractedReportText;
        this.parsedFindings = parsedFindings;
        this.summaryResult = summaryResult;
    }

    public String getExtractedReportText() {
        return extractedReportText;
    }

    public List<ParsedFindingCandidate> getParsedFindings() {
        return parsedFindings;
    }

    public SummaryResult getSummaryResult() {
        return summaryResult;
    }

    public boolean hasExtractedText() {
        return StringUtils.hasText(extractedReportText);
    }

    public boolean hasParsedFindings() {
        return parsedFindings != null && !parsedFindings.isEmpty();
    }

    public boolean hasSummary() {
        return summaryResult != null && StringUtils.hasText(summaryResult.getMainSummary());
    }
}

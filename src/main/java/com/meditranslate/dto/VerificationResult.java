package com.meditranslate.dto;

import com.meditranslate.entity.ReportFinding;
import java.util.List;

public class VerificationResult {

    private final List<ReportFinding> findings;
    private final double trustScore;

    public VerificationResult(List<ReportFinding> findings, double trustScore) {
        this.findings = findings;
        this.trustScore = trustScore;
    }

    public List<ReportFinding> getFindings() {
        return findings;
    }

    public double getTrustScore() {
        return trustScore;
    }
}

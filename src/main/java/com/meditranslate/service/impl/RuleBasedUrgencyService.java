package com.meditranslate.service.impl;

import com.meditranslate.entity.FindingStatus;
import com.meditranslate.entity.ReportFinding;
import com.meditranslate.entity.UrgencyLevel;
import com.meditranslate.service.UrgencyService;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class RuleBasedUrgencyService implements UrgencyService {

    @Override
    public UrgencyLevel determineUrgency(List<ReportFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return UrgencyLevel.LOW;
        }

        int abnormalCount = 0;
        boolean criticalVariance = false;
        boolean descriptiveConcern = false;

        for (ReportFinding finding : findings) {
            if (finding.isAbnormal()) {
                abnormalCount++;
            }

            if (finding.getStatus() == FindingStatus.POSITIVE || finding.getStatus() == FindingStatus.ABNORMAL) {
                descriptiveConcern = true;
            }

            if (finding.getRangeLow() != null && finding.getRangeHigh() != null && finding.getPatientValue() != null) {
                try {
                    BigDecimal patientValue = new BigDecimal(finding.getPatientValue().replaceAll("[^0-9.]", ""));
                    BigDecimal low = finding.getRangeLow();
                    BigDecimal high = finding.getRangeHigh();
                    BigDecimal rangeSize = high.subtract(low);
                    if (rangeSize.signum() > 0) {
                        if (patientValue.compareTo(low) < 0 && low.subtract(patientValue).compareTo(rangeSize.multiply(new BigDecimal("0.25"))) > 0) {
                            criticalVariance = true;
                        }
                        if (patientValue.compareTo(high) > 0 && patientValue.subtract(high).compareTo(rangeSize.multiply(new BigDecimal("0.25"))) > 0) {
                            criticalVariance = true;
                        }
                    }
                } catch (NumberFormatException ignored) {
                    // Keep urgency resilient when OCR introduces noisy values.
                }
            }
        }

        if (criticalVariance || abnormalCount >= 3 || descriptiveConcern) {
            return UrgencyLevel.HIGH;
        }
        if (abnormalCount >= 1) {
            return UrgencyLevel.MEDIUM;
        }
        return UrgencyLevel.LOW;
    }
}

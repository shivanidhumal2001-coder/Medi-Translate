package com.meditranslate.service;

import com.meditranslate.entity.ReportFinding;
import com.meditranslate.entity.UrgencyLevel;
import java.util.List;

public interface UrgencyService {
    UrgencyLevel determineUrgency(List<ReportFinding> findings);
}

package com.meditranslate.service;

import com.meditranslate.dto.SymptomInsightDto;
import com.meditranslate.entity.ReportAnalysis;
import java.util.List;

public interface SymptomMatcherService {
    List<SymptomInsightDto> matchSymptoms(ReportAnalysis report, String symptomsInput);
}

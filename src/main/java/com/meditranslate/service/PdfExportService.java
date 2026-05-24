package com.meditranslate.service;

import com.meditranslate.entity.ReportAnalysis;

public interface PdfExportService {
    byte[] exportReport(ReportAnalysis report);
}

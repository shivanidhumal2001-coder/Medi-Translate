package com.meditranslate.service;

import com.meditranslate.dto.ExtractionResult;
import java.io.IOException;
import org.springframework.web.multipart.MultipartFile;

public interface ReportExtractionService {
    ExtractionResult extract(String typedText, MultipartFile file) throws IOException;
}

package com.meditranslate.service.impl;

import com.meditranslate.dto.ParsedFindingCandidate;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegexFindingParserServiceTest {

    private final RegexFindingParserService parser = new RegexFindingParserService();

    @Test
    void parsesSeparatedCbcTableLayoutIntoStructuredFindings() {
        String extractedText = """
                Diagnostic Center
                Advanced Pathology Lab
                INVESTIGATION
                RBC PARAMETERS
                Haemoglobin
                Total R.B.C. Count
                PCV/HCT
                MCV
                MCH
                MCHC
                RDW
                WBC PARAMETERS:
                Total W.B.C. Count
                Neutrophils
                Lymphocytes
                Monocytes
                Eosinophils
                Basophils
                Band Forms
                Absolute Neutrophils
                Absolute Lymphocyte
                Absolute Monocytes
                Absolute Eosinophils
                Absolute Basophils
                PLATELET PARAMETERS
                Platelet Count
                MPV
                PERIPHERIAL SMEAR FINDINGS:
                Morphology of R.B.C.s
                Morphology of W.B.C.s
                RESULT REF RANGE
                11.7 12 to 16
                4.14 3.8 to 5.8
                37.0 37 to 47
                89.4 76 to 96
                28.3 27 to 32
                31.6 30 to 35
                14.3 11.5 to 14.5
                6000 4000 to 11000
                65.0 40 to 75
                30.0 20 to 40
                3.0 0 to 10
                2.0 0 to 6
                0.0 0 to 1
                0.0 0 to 0
                3.9 2.00 to 7.00
                1.8 1.00 to 3.00
                0.2 0.20 to 1.00
                0.1 0.02 to 0.50
                0.0 0.02 to 0.10
                297000 150000 to 450000
                10.50 6 to 11
                END OF REPORT
                """;

        List<ParsedFindingCandidate> findings = parser.parseFindings(extractedText);

        assertTrue(findings.size() >= 10);

        ParsedFindingCandidate hemoglobin = findings.stream()
                .filter(candidate -> "Hemoglobin".equals(candidate.getParameterName()))
                .findFirst()
                .orElse(null);

        assertNotNull(hemoglobin);
        assertEquals("11.7", hemoglobin.getPatientValue());
        assertEquals("12 - 16", hemoglobin.getPrintedRangeText());

        ParsedFindingCandidate plateletCount = findings.stream()
                .filter(candidate -> "Platelet Count".equals(candidate.getParameterName()))
                .findFirst()
                .orElse(null);

        assertNotNull(plateletCount);
        assertEquals("297000", plateletCount.getPatientValue());
        assertEquals("150000 - 450000", plateletCount.getPrintedRangeText());
    }
}

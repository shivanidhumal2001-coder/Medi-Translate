package com.meditranslate.dto;

import java.math.BigDecimal;

public class ParsedFindingCandidate {

    private String parameterName;
    private String rawLine;
    private String patientValue;
    private BigDecimal numericValue;
    private String unit;
    private BigDecimal printedRangeLow;
    private BigDecimal printedRangeHigh;
    private String printedRangeText;
    private String descriptiveValue;

    public String getParameterName() {
        return parameterName;
    }

    public void setParameterName(String parameterName) {
        this.parameterName = parameterName;
    }

    public String getRawLine() {
        return rawLine;
    }

    public void setRawLine(String rawLine) {
        this.rawLine = rawLine;
    }

    public String getPatientValue() {
        return patientValue;
    }

    public void setPatientValue(String patientValue) {
        this.patientValue = patientValue;
    }

    public BigDecimal getNumericValue() {
        return numericValue;
    }

    public void setNumericValue(BigDecimal numericValue) {
        this.numericValue = numericValue;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public BigDecimal getPrintedRangeLow() {
        return printedRangeLow;
    }

    public void setPrintedRangeLow(BigDecimal printedRangeLow) {
        this.printedRangeLow = printedRangeLow;
    }

    public BigDecimal getPrintedRangeHigh() {
        return printedRangeHigh;
    }

    public void setPrintedRangeHigh(BigDecimal printedRangeHigh) {
        this.printedRangeHigh = printedRangeHigh;
    }

    public String getPrintedRangeText() {
        return printedRangeText;
    }

    public void setPrintedRangeText(String printedRangeText) {
        this.printedRangeText = printedRangeText;
    }

    public String getDescriptiveValue() {
        return descriptiveValue;
    }

    public void setDescriptiveValue(String descriptiveValue) {
        this.descriptiveValue = descriptiveValue;
    }

    public boolean hasNumericValue() {
        return numericValue != null;
    }

    public boolean hasPrintedRange() {
        return printedRangeLow != null && printedRangeHigh != null;
    }

    public boolean hasDescriptiveValue() {
        return descriptiveValue != null && !descriptiveValue.isBlank();
    }
}

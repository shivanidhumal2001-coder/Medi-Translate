package com.meditranslate.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "report_findings")
public class ReportFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "report_id", nullable = false)
    private ReportAnalysis report;

    @Column(nullable = false, length = 180)
    private String parameterName;

    @Column(length = 500)
    private String rawLine;

    @Column(length = 80)
    private String patientValue;

    @Column(length = 40)
    private String unit;

    @Column(length = 120)
    private String normalRangeText;

    private BigDecimal rangeLow;

    private BigDecimal rangeHigh;

    @Column(length = 160)
    private String aiInterpretation;

    @Column(length = 160)
    private String systemInterpretation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FindingStatus status = FindingStatus.UNKNOWN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private VerificationLayer verificationLayer = VerificationLayer.DESCRIPTIVE_RULE;

    @Column(length = 180)
    private String evidenceSource;

    @Column(nullable = false)
    private boolean matched;

    @Column(nullable = false)
    private boolean abnormal;

    public Long getId() {
        return id;
    }

    public ReportAnalysis getReport() {
        return report;
    }

    public void setReport(ReportAnalysis report) {
        this.report = report;
    }

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

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public String getNormalRangeText() {
        return normalRangeText;
    }

    public void setNormalRangeText(String normalRangeText) {
        this.normalRangeText = normalRangeText;
    }

    public BigDecimal getRangeLow() {
        return rangeLow;
    }

    public void setRangeLow(BigDecimal rangeLow) {
        this.rangeLow = rangeLow;
    }

    public BigDecimal getRangeHigh() {
        return rangeHigh;
    }

    public void setRangeHigh(BigDecimal rangeHigh) {
        this.rangeHigh = rangeHigh;
    }

    public String getAiInterpretation() {
        return aiInterpretation;
    }

    public void setAiInterpretation(String aiInterpretation) {
        this.aiInterpretation = aiInterpretation;
    }

    public String getSystemInterpretation() {
        return systemInterpretation;
    }

    public void setSystemInterpretation(String systemInterpretation) {
        this.systemInterpretation = systemInterpretation;
    }

    public FindingStatus getStatus() {
        return status;
    }

    public void setStatus(FindingStatus status) {
        this.status = status;
    }

    public VerificationLayer getVerificationLayer() {
        return verificationLayer;
    }

    public void setVerificationLayer(VerificationLayer verificationLayer) {
        this.verificationLayer = verificationLayer;
    }

    public String getEvidenceSource() {
        return evidenceSource;
    }

    public void setEvidenceSource(String evidenceSource) {
        this.evidenceSource = evidenceSource;
    }

    public boolean isMatched() {
        return matched;
    }

    public void setMatched(boolean matched) {
        this.matched = matched;
    }

    public boolean isAbnormal() {
        return abnormal;
    }

    public void setAbnormal(boolean abnormal) {
        this.abnormal = abnormal;
    }
}

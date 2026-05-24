package com.meditranslate.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "reference_ranges")
public class ReferenceRange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 140)
    private String analyteName;

    @Column(length = 40)
    private String unit;

    @Column(nullable = false)
    private BigDecimal lowValue;

    @Column(nullable = false)
    private BigDecimal highValue;

    @Column(nullable = false, length = 80)
    private String sourceName;

    @Column(length = 240)
    private String notes;

    public ReferenceRange() {
    }

    public ReferenceRange(String analyteName, String unit, BigDecimal lowValue, BigDecimal highValue,
                          String sourceName, String notes) {
        this.analyteName = analyteName;
        this.unit = unit;
        this.lowValue = lowValue;
        this.highValue = highValue;
        this.sourceName = sourceName;
        this.notes = notes;
    }

    public Long getId() {
        return id;
    }

    public String getAnalyteName() {
        return analyteName;
    }

    public String getUnit() {
        return unit;
    }

    public BigDecimal getLowValue() {
        return lowValue;
    }

    public BigDecimal getHighValue() {
        return highValue;
    }

    public String getSourceName() {
        return sourceName;
    }

    public String getNotes() {
        return notes;
    }
}

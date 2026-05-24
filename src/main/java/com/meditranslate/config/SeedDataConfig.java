package com.meditranslate.config;

import com.meditranslate.entity.Role;
import com.meditranslate.entity.ReferenceRange;
import com.meditranslate.repository.ReferenceRangeRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class SeedDataConfig {

    @Bean
    CommandLineRunner seedReferenceRanges(ReferenceRangeRepository referenceRangeRepository) {
        return args -> {
            if (referenceRangeRepository.count() > 0) {
                return;
            }

            List<ReferenceRange> ranges = List.of(
                    new ReferenceRange("Hemoglobin", "g/dL", new BigDecimal("12.0"), new BigDecimal("16.0"), "WHO", "Adult reference range"),
                    new ReferenceRange("WBC", "/uL", new BigDecimal("4000"), new BigDecimal("11000"), "ICMR", "Total white blood cell count"),
                    new ReferenceRange("Platelet Count", "/uL", new BigDecimal("150000"), new BigDecimal("450000"), "WHO", "Platelet reference range"),
                    new ReferenceRange("RBC", "million/uL", new BigDecimal("4.2"), new BigDecimal("5.4"), "WHO", "Red blood cell count"),
                    new ReferenceRange("Fasting Glucose", "mg/dL", new BigDecimal("70"), new BigDecimal("100"), "WHO", "Fasting sugar guidance"),
                    new ReferenceRange("HbA1c", "%", new BigDecimal("4.0"), new BigDecimal("5.6"), "WHO", "Estimated average glucose control"),
                    new ReferenceRange("Creatinine", "mg/dL", new BigDecimal("0.6"), new BigDecimal("1.3"), "WHO", "Kidney function marker"),
                    new ReferenceRange("Total Cholesterol", "mg/dL", new BigDecimal("125"), new BigDecimal("200"), "WHO", "Lipid profile range"),
                    new ReferenceRange("TSH", "uIU/mL", new BigDecimal("0.4"), new BigDecimal("4.0"), "ICMR", "Thyroid stimulating hormone"),
                    new ReferenceRange("Vitamin D", "ng/mL", new BigDecimal("30"), new BigDecimal("100"), "WHO", "25-OH Vitamin D reference range")
            );

            referenceRangeRepository.saveAll(ranges);
        };
    }

    @Bean
    CommandLineRunner normalizeLegacyUserRoles(JdbcTemplate jdbcTemplate) {
        return args -> {
            jdbcTemplate.update(
                    "UPDATE users SET role = ? WHERE UPPER(role) IN (?, ?)",
                    Role.ROLE_USER.name(),
                    "CUSTOMER",
                    "USER"
            );
            jdbcTemplate.update(
                    "UPDATE users SET role = ? WHERE UPPER(role) = ?",
                    Role.ROLE_ADMIN.name(),
                    "ADMIN"
            );
        };
    }
}

package com.meditranslate.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

@Converter(autoApply = true)
public class RoleConverter implements AttributeConverter<Role, String> {

    @Override
    public String convertToDatabaseColumn(Role attribute) {
        return (attribute == null ? Role.ROLE_USER : attribute).name();
    }

    @Override
    public Role convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return Role.ROLE_USER;
        }

        String normalizedValue = dbData.trim().toUpperCase(Locale.ROOT);
        return switch (normalizedValue) {
            case "ROLE_USER", "CUSTOMER", "USER" -> Role.ROLE_USER;
            case "ROLE_ADMIN", "ADMIN" -> Role.ROLE_ADMIN;
            default -> throw new IllegalArgumentException("Unsupported role value: " + dbData);
        };
    }
}

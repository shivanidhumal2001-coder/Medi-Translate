package com.meditranslate.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RoleConverterTest {

    private final RoleConverter converter = new RoleConverter();

    @Test
    void mapsLegacyCustomerRoleToRoleUser() {
        assertEquals(Role.ROLE_USER, converter.convertToEntityAttribute("CUSTOMER"));
    }

    @Test
    void mapsLegacyUserRoleToRoleUser() {
        assertEquals(Role.ROLE_USER, converter.convertToEntityAttribute("USER"));
    }

    @Test
    void persistsNormalizedRoleUserValue() {
        assertEquals("ROLE_USER", converter.convertToDatabaseColumn(Role.ROLE_USER));
    }

    @Test
    void mapsLegacyAdminRoleToRoleAdmin() {
        assertEquals(Role.ROLE_ADMIN, converter.convertToEntityAttribute("ADMIN"));
    }

    @Test
    void persistsNormalizedRoleAdminValue() {
        assertEquals("ROLE_ADMIN", converter.convertToDatabaseColumn(Role.ROLE_ADMIN));
    }

    @Test
    void rejectsUnexpectedRoleValues() {
        assertThrows(IllegalArgumentException.class, () -> converter.convertToEntityAttribute("SUPER_ADMIN"));
    }
}

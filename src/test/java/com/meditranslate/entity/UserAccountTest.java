package com.meditranslate.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class UserAccountTest {

    @Test
    void derivesLegacyUsernameFromEmailWhenMissing() {
        UserAccount user = new UserAccount();
        user.setEmail("Doctor.User@example.com");

        user.onCreate();

        assertEquals("doctor.user", user.getAccountUsername());
        assertEquals(Role.ROLE_USER, user.getRole());
        assertNotNull(user.getCreatedAt());
    }

    @Test
    void keepsExplicitLegacyUsernameWhenProvided() {
        UserAccount user = new UserAccount();
        user.setEmail("person@example.com");
        user.setAccountUsername("custom-name");

        user.onCreate();

        assertEquals("custom-name", user.getAccountUsername());
    }
}

package com.meditranslate.service.impl;

import com.meditranslate.service.GuestSessionService;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DefaultGuestSessionService implements GuestSessionService {

    private static final String GUEST_SESSION_KEY = "mediTranslateGuestToken";

    @Override
    public String getOrCreateSessionToken(HttpSession session) {
        Object existingToken = session.getAttribute(GUEST_SESSION_KEY);
        if (existingToken instanceof String token && !token.isBlank()) {
            return token;
        }
        String token = UUID.randomUUID().toString();
        session.setAttribute(GUEST_SESSION_KEY, token);
        return token;
    }
}

package com.meditranslate.service;

import jakarta.servlet.http.HttpSession;

public interface GuestSessionService {
    String getOrCreateSessionToken(HttpSession session);
}

package com.meditranslate.service;

import com.meditranslate.dto.RegistrationForm;
import com.meditranslate.entity.UserAccount;

public interface UserRegistrationService {
    UserAccount register(RegistrationForm form);
}

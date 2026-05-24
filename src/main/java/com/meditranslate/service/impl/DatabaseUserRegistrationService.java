package com.meditranslate.service.impl;

import com.meditranslate.dto.RegistrationForm;
import com.meditranslate.entity.UserAccount;
import com.meditranslate.repository.UserRepository;
import com.meditranslate.service.UserRegistrationService;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatabaseUserRegistrationService implements UserRegistrationService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DatabaseUserRegistrationService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public UserAccount register(RegistrationForm form) {
        String normalizedEmail = form.getEmail().trim().toLowerCase(Locale.ROOT);

        if (!form.getPassword().equals(form.getConfirmPassword())) {
            throw new IllegalArgumentException("Passwords do not match");
        }
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new IllegalArgumentException("An account already exists for this email");
        }

        UserAccount user = new UserAccount();
        user.setFullName(form.getFullName().trim());
        user.setEmail(normalizedEmail);
        user.setAccountUsername(UserAccount.deriveAccountUsername(normalizedEmail));
        user.setPasswordHash(passwordEncoder.encode(form.getPassword()));
        user.setPreferredLanguage(form.getPreferredLanguage());
        return userRepository.save(user);
    }
}

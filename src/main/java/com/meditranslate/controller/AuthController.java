package com.meditranslate.controller;

import com.meditranslate.dto.RegistrationForm;
import com.meditranslate.service.UserRegistrationService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import com.meditranslate.entity.UserAccount;

@Controller
public class AuthController {

    private final UserRegistrationService userRegistrationService;

    public AuthController(UserRegistrationService userRegistrationService) {
        this.userRegistrationService = userRegistrationService;
    }

    @GetMapping("/auth/login")
    public String login(@AuthenticationPrincipal UserAccount user) {
        if (user != null) {
            return "redirect:/dashboard";
        }
        return "auth/login";
    }

    @GetMapping("/auth/register")
    public String registerForm(Model model, @AuthenticationPrincipal UserAccount user) {
        if (user != null) {
            return "redirect:/dashboard";
        }
        model.addAttribute("registrationForm", new RegistrationForm());
        return "auth/register";
    }

    @PostMapping("/auth/register")
    public String register(@Valid @ModelAttribute("registrationForm") RegistrationForm registrationForm,
                           BindingResult bindingResult,
                           Model model) {
        if (bindingResult.hasErrors()) {
            return "auth/register";
        }

        try {
            userRegistrationService.register(registrationForm);
            return "redirect:/auth/login?registered";
        } catch (IllegalArgumentException ex) {
            model.addAttribute("registrationError", ex.getMessage());
            return "auth/register";
        }
    }
}

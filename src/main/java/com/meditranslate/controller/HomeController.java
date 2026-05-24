package com.meditranslate.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import com.meditranslate.entity.UserAccount;

@Controller
public class HomeController {

    @GetMapping("/")
    public String home(@AuthenticationPrincipal UserAccount user, Model model) {
        model.addAttribute("currentUser", user);
        return "index";
    }
}

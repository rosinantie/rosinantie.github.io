package com.example.newapp.authentication.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.example.newapp.authentication.config.AppSecurityProperties;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Without this, Spring Security's default post-login target "/" has nothing
 * mapped and falls through to a 404 Whitelabel page.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class PageController {

    private final AppSecurityProperties properties;

    @GetMapping("/")
    public String home(Authentication authentication, Model model) {
        log.info("GET / (home) for authenticated username='{}'", authentication.getName());
        model.addAttribute("username", authentication.getName());
        return "home";
    }

    @GetMapping("/login")
    public String login(Model model) {
        log.info("GET /login, googleLoginEnabled={}", properties.googleLogin().isConfigured());
        model.addAttribute("googleLoginEnabled", properties.googleLogin().isConfigured());
        return "login";
    }

    @GetMapping("/register")
    public String registerPage() {
        log.info("GET /register");
        return "register";
    }

    /** Chrome silently probes this on every page load; answer quietly instead of a 404 error page. */
    @GetMapping("/.well-known/appspecific/com.chrome.devtools.json")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void chromeDevtoolsProbe() {
    }
}

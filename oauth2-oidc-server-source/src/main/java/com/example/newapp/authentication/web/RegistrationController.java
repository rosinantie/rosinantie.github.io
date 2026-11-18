package com.example.newapp.authentication.web;

import com.example.newapp.authentication.entity.AppUser;
import com.example.newapp.authentication.entity.Role;
import com.example.newapp.authentication.repository.AppUserRepository;
import com.example.newapp.authentication.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.HashSet;
import java.util.Set;

@Slf4j
@Controller
@RequiredArgsConstructor
public class RegistrationController {

    private final AppUserRepository appUserRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @PostMapping("/register")
    public String register(@RequestParam String username,
                            @RequestParam String email,
                            @RequestParam String password,
                            Model model) {

        log.info("POST /register username='{}' email='{}'", username, email);

        if (username.isBlank() || email.isBlank() || password.length() < 8) {
            log.warn("REGISTRATION REJECTED username='{}': missing fields or password too short", username);
            model.addAttribute("error", "Username/email required, password must be at least 8 characters.");
            return "register";
        }
        if (appUserRepository.existsByUsername(username)) {
            log.warn("REGISTRATION REJECTED username='{}': already taken", username);
            model.addAttribute("error", "That username is already taken.");
            return "register";
        }
        if (appUserRepository.existsByEmail(email)) {
            log.warn("REGISTRATION REJECTED email='{}': already registered", email);
            model.addAttribute("error", "That email is already registered.");
            return "register";
        }

        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseThrow(() -> new IllegalStateException("ROLE_USER not seeded - see RoleSeeder"));
        AppUser user = AppUser.builder()
                .username(username)
                .email(email)
                .password(passwordEncoder.encode(password))
                .roles(new HashSet<>(Set.of(userRole)))
                .build();
        appUserRepository.save(user);
        log.info("REGISTRATION OK username='{}' id={}", username, user.getId());

        return "redirect:/login?registered";
    }
}

package com.example.newapp.authentication.config;

import com.example.newapp.authentication.security.GoogleLoginSuccessHandler;
import com.example.newapp.authentication.security.LockoutAwareFailureHandler;
import com.example.newapp.authentication.security.LoginSuccessHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Everything that is NOT an /oauth2/** or /.well-known/** endpoint: login UI, registration, logout. */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DefaultSecurityConfig {

    private final LoginSuccessHandler loginSuccessHandler;
    private final LockoutAwareFailureHandler lockoutAwareFailureHandler;
    private final GoogleLoginSuccessHandler googleLoginSuccessHandler;

    @Bean
    @Order(2)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        log.info("BUILDING default security filter chain (login UI, registration, logout)");
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/login", "/register", "/error",
                                "/webjars/**", "/css/**", "/js/**", "/favicon.ico",
                                "/.well-known/appspecific/com.chrome.devtools.json").permitAll()
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .successHandler(loginSuccessHandler)
                        .failureHandler(lockoutAwareFailureHandler)
                        .permitAll()
                )
                .oauth2Login(oauth2 -> oauth2
                        .loginPage("/login")
                        .successHandler(googleLoginSuccessHandler)
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .permitAll()
                );

        return http.build();
    }
}

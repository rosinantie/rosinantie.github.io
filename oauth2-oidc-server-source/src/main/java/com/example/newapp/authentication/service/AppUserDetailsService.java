package com.example.newapp.authentication.service;

import com.example.newapp.authentication.repository.AppUserRepository;
import com.example.newapp.authentication.security.AppUserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository appUserRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        log.info("Looking up user by username='{}'", username);
        return appUserRepository.findByUsername(username)
                .map(user -> {
                    log.info("User found: username='{}' enabled={} locked={}", user.getUsername(), user.isEnabled(), user.isLocked());
                    return (UserDetails) new AppUserPrincipal(user);
                })
                .orElseThrow(() -> {
                    log.warn("No user found for username='{}'", username);
                    return new UsernameNotFoundException("No user: " + username);
                });
    }
}

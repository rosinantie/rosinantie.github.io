package com.example.newapp.authentication.security;

import com.example.newapp.authentication.entity.AppUser;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
public class AppUserPrincipal implements UserDetails {

    private final AppUser appUser;

    public AppUserPrincipal(AppUser appUser) {
        this.appUser = appUser;
    }

    public UUID getId() {
        return appUser.getId();
    }

    public String getEmail() {
        return appUser.getEmail();
    }

    /**
     * Role names (e.g. "ROLE_USER") AND each role's individual permissions (e.g.
     * "users:read") both become authorities, so both hasRole(...) and the
     * finer-grained hasAuthority(...) work against the same principal.
     */
    @Override
    public List<? extends GrantedAuthority> getAuthorities() {
        List<GrantedAuthority> authorities = new ArrayList<>();
        appUser.getRoleNames().forEach(name -> authorities.add(new SimpleGrantedAuthority(name)));
        appUser.getPermissionNames().forEach(name -> authorities.add(new SimpleGrantedAuthority(name)));
        return authorities;
    }

    @Override
    public String getPassword() {
        return appUser.getPassword();
    }

    @Override
    public String getUsername() {
        return appUser.getUsername();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return !appUser.isLocked();
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return appUser.isEnabled();
    }
}

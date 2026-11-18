package com.example.newapp.authentication.config;

import com.example.newapp.authentication.entity.Permission;
import com.example.newapp.authentication.entity.Role;
import com.example.newapp.authentication.repository.PermissionRepository;
import com.example.newapp.authentication.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Idempotent baseline data for the roles/permissions model. Runs on every
 * startup: creating already-existing rows is a no-op, so this is safe to
 * leave in place rather than a one-shot migration script.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoleSeeder implements CommandLineRunner {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;

    @Override
    @Transactional
    public void run(String... args) {
        log.info("Seeding baseline roles and permissions");
        Map<String, Permission> permissions = ensurePermissions();
        ensureRole("ROLE_USER", "Standard authenticated user", List.of(
                permissions.get("profile:read"), permissions.get("profile:write")));
        ensureRole("ROLE_ADMIN", "Full administrative access", List.copyOf(permissions.values()));
        log.info("Role/permission seeding complete");
    }

    private Map<String, Permission> ensurePermissions() {
        Map<String, String> baseline = new LinkedHashMap<>();
        baseline.put("profile:read", "View your own profile");
        baseline.put("profile:write", "Edit your own profile");
        baseline.put("users:read", "View other users' accounts");
        baseline.put("users:write", "Enable/disable other users' accounts");

        Map<String, Permission> result = new LinkedHashMap<>();
        baseline.forEach((name, description) -> result.put(name, ensurePermission(name, description)));
        return result;
    }

    private Permission ensurePermission(String name, String description) {
        return permissionRepository.findByName(name)
                .orElseGet(() -> {
                    log.info("Creating permission '{}'", name);
                    return permissionRepository.save(
                            Permission.builder().name(name).description(description).build());
                });
    }

    private void ensureRole(String name, String description, List<Permission> permissions) {
        roleRepository.findByName(name)
                .orElseGet(() -> {
                    log.info("Creating role '{}'", name);
                    return roleRepository.save(
                            Role.builder().name(name).description(description).permissions(new HashSet<>(permissions)).build());
                });
    }
}

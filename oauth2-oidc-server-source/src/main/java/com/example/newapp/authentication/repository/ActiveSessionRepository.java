package com.example.newapp.authentication.repository;

import com.example.newapp.authentication.entity.ActiveSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ActiveSessionRepository extends JpaRepository<ActiveSession, UUID> {
    Optional<ActiveSession> findByAuthorizationId(String authorizationId);

    List<ActiveSession> findByUsernameOrderByLastUsedAtDesc(String username);

    void deleteByAuthorizationId(String authorizationId);
}

package com.example.newapp.authentication.repository;

import com.example.newapp.authentication.entity.LoginHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LoginHistoryRepository extends JpaRepository<LoginHistory, UUID> {
    List<LoginHistory> findTop50ByUsernameOrderByOccurredAtDesc(String username);
}

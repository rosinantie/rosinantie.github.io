package com.example.newapp.authentication.repository;

import com.example.newapp.authentication.entity.PasskeyCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PasskeyCredentialRepository extends JpaRepository<PasskeyCredential, UUID> {
    Optional<PasskeyCredential> findByCredentialId(byte[] credentialId);
    List<PasskeyCredential> findByUserEntityUserId(byte[] userEntityUserId);
}

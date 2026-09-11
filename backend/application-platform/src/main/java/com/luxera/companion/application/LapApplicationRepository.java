package com.luxera.companion.application;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LapApplicationRepository extends JpaRepository<LapApplication, String> {

    Optional<LapApplication> findByCode(String code);

    Optional<LapApplication> findByCodeAndStatus(String code, String status);
}
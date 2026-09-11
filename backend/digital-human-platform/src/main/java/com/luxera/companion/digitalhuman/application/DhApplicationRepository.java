package com.luxera.companion.digitalhuman.application;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DhApplicationRepository extends JpaRepository<DhApplication, String> {

    Optional<DhApplication> findByCode(String code);

    Optional<DhApplication> findByCodeAndStatus(String code, String status);
}
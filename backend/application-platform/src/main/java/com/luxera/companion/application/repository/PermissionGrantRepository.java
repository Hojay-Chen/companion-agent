package com.luxera.companion.application.repository;

import com.luxera.companion.application.domain.PermissionGrantRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PermissionGrantRepository extends JpaRepository<PermissionGrantRecord, String> {

    List<PermissionGrantRecord> findByInstallationId(String installationId);

    void deleteByInstallationId(String installationId);
}

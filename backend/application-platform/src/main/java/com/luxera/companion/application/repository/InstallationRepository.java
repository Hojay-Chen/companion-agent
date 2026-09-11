package com.luxera.companion.application.repository;

import com.luxera.companion.application.domain.InstallationRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InstallationRepository extends JpaRepository<InstallationRecord, String> {

    /** 唯一键的精确形态 —— 归属判定的第一跳。 */
    Optional<InstallationRecord> findByApplicationIdAndPrincipalTypeAndPrincipalId(
            String applicationId, String principalType, String principalId);

    List<InstallationRecord> findByPrincipalTypeAndPrincipalId(String principalType, String principalId);

    List<InstallationRecord> findByApplicationId(String applicationId);
}

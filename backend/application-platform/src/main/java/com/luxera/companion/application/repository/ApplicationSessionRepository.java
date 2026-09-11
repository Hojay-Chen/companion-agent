package com.luxera.companion.application.repository;

import com.luxera.companion.application.domain.ApplicationSessionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ApplicationSessionRepository extends JpaRepository<ApplicationSessionRecord, String> {

    List<ApplicationSessionRecord> findByInstallationId(String installationId);

    List<ApplicationSessionRecord> findByApplicationIdAndPrincipalTypeAndPrincipalId(
            String applicationId, String principalType, String principalId);

    /** 回收用: 长期没有动作的会话。 */
    List<ApplicationSessionRecord> findByStatusAndLastActiveAtBefore(String status, LocalDateTime before);

    List<ApplicationSessionRecord> findByCompanionIdAndStatus(String companionId, String status);
}

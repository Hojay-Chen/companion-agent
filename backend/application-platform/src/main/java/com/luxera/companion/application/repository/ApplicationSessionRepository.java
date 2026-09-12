package com.luxera.companion.application.repository;

import com.luxera.companion.application.domain.ApplicationSessionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ApplicationSessionRepository extends JpaRepository<ApplicationSessionRecord, String> {

    List<ApplicationSessionRecord> findByApplicationId(String applicationId);

    /** 回收用: 长期没有动作的会话。 */
    List<ApplicationSessionRecord> findByStatusAndLastActiveAtBefore(String status, LocalDateTime before);

    /** 会话解析第 4 档: 这个 principal 开的、在这个应用下最近的活跃会话。 */
    Optional<ApplicationSessionRecord> findFirstByOwnerPrincipalTypeAndOwnerPrincipalIdAndApplicationIdAndStatusOrderByLastActiveAtDesc(
            String ownerPrincipalType, String ownerPrincipalId, String applicationId, String status);
}

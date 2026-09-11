package com.luxera.companion.application.repository;

import com.luxera.companion.application.domain.ActionInvocationRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ActionInvocationRepository extends JpaRepository<ActionInvocationRecord, String> {

    Optional<ActionInvocationRecord> findByPrincipalTypeAndPrincipalIdAndIdempotencyKey(
            String principalType, String principalId, String idempotencyKey);

    /**
     * 崩溃遗留的抢占: <b>CAS 到行上, 不是先读后改。</b>
     *
     * <p>条件里带 {@code startedAt < staleBefore} 而不是只带 id —— 两个请求同时发现同一条
     * 超时的 IN_PROGRESS 时, 只有一个人能把 started_at 推到现在, 另一个影响 0 行, 于是回到
     * "执行中"分支。先读后改会让两边都以为自己抢到了, 然后同一步棋落两次。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ActionInvocationRecord i set i.startedAt = :now, "
            + "i.attemptCount = i.attemptCount + 1 "
            + "where i.id = :id and i.status = :inProgress and i.startedAt < :staleBefore")
    int claimStale(@Param("id") String id,
                   @Param("inProgress") String inProgress,
                   @Param("staleBefore") LocalDateTime staleBefore,
                   @Param("now") LocalDateTime now);

    /** 回收器扫描: 卡在执行中且已超时的调用。 */
    List<ActionInvocationRecord> findByStatusAndStartedAtBefore(String status, LocalDateTime staleBefore);
}

package com.luxera.companion.application.audit;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * V10 §14/LAP §48 动作审计日志。
 *
 * 记录每一次应用动作的执行: 谁(companion)对哪个应用做了什么、结果如何、经什么权限决策。
 * 可追溯"为什么 Agent 给我下单了"这类问题。
 */
@Entity
@Table(name = "dh_application_action_log")
@Getter
@Setter
public class ActionLogRecord {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "action_id", nullable = false, length = 128)
    private String actionId;

    @Column(name = "app_code", length = 64)
    private String appCode;

    @Column(name = "companion_id", length = 36)
    private String companionId;

    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(name = "session_id", length = 36)
    private String sessionId;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "permission_decision", length = 32)
    private String permissionDecision;

    @Column(name = "execution_status", length = 32)
    private String executionStatus;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @PrePersist
    void assignId() {
        if (id == null) id = UUID.randomUUID().toString();
        if (startedAt == null) startedAt = LocalDateTime.now();
    }
}
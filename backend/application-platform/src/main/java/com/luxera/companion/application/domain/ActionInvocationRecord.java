package com.luxera.companion.application.domain;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * LAP v1: 幂等的物理形态。
 *
 * <p>唯一键 {@code (principal_type, principal_id, idempotency_key)} —— 作用域里必须带 principal,
 * 否则真人的 key 会和 Agent 的撞车。
 *
 * <p>{@code request_hash} 防"同 key 不同载荷": 同一个 key 配上不同的 body 是客户端 bug,
 * 静默按第一次的结果返回会让那个 bug 极其难查, 所以直接 422。
 *
 * <p><b>禁止 check → insert → execute</b>(那是 TOCTOU)。正确次序是
 * "插入 IN_PROGRESS(唯一索引即锁, 插入失败即已存在)→ 提交 → 执行 → 回填终态"。
 * 崩溃恢复靠 {@code started_at} 超时 + CAS 抢占, 见 {@code ActionInvocationReaperJob}。
 */
@Entity
@Table(name = "action_invocation",
        uniqueConstraints = @UniqueConstraint(name = "uq_action_invocation_key",
                columnNames = {"principal_type", "principal_id", "idempotency_key"}))
@Getter
@Setter
public class ActionInvocationRecord {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    @Column(name = "principal_type", nullable = false, length = 16)
    private String principalType;

    @Column(name = "principal_id", nullable = false, length = 64)
    private String principalId;

    @Column(name = "action_id", nullable = false, length = 128)
    private String actionId;

    @Column(name = "application_id", nullable = false, length = 128)
    private String applicationId;

    @Column(name = "session_id", length = 36)
    private String sessionId;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(nullable = false, length = 32)
    private String status = InvocationStatus.IN_PROGRESS.name();

    /** 终态时回填的完整 ActionResponse JSON —— 重放时逐字节返回它。 */
    @Column(name = "response_json", columnDefinition = "text")
    private String responseJson;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    /** 崩溃恢复的判据: {@code now - started_at > timeout} 才允许抢占。 */
    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** 重试计数, 防无限重放。 */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 1;

    public InvocationStatus statusEnum() {
        return InvocationStatus.valueOf(status);
    }

    public boolean terminal() {
        return statusEnum().terminal();
    }

    @PrePersist
    void assignId() {
        if (id == null) id = UUID.randomUUID().toString();
        if (startedAt == null) startedAt = LocalDateTime.now();
    }
}

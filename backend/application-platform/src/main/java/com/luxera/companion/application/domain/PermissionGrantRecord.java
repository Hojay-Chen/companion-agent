package com.luxera.companion.application.domain;

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
 * LAP v1: 一次安装换来的授权。权限 = <b>Principal × Installation grant × Capability ×
 * Action × Risk</b>, 这张表是其中的 grant 一维。
 *
 * <p>表名是 {@code permission_grant} 而不是方案原文的 {@code grant}: 后者是 PostgreSQL
 * 保留字, {@code create table grant (...)} 直接语法错误 —— 照着写会在第一次建表时炸掉。
 *
 * <p>{@code capability_id} 与 {@code action_id} 至少一个非空: 授权可以是"这个能力都能用",
 * 也可以是"只准用这一个动作"。两个都空意味着一条什么都不允许的授权, 那是数据错误。
 */
@Entity
@Table(name = "permission_grant")
@Getter
@Setter
public class PermissionGrantRecord {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "installation_id", nullable = false, length = 36)
    private String installationId;

    @Column(name = "capability_id", length = 64)
    private String capabilityId;

    @Column(name = "action_id", length = 128)
    private String actionId;

    @Column(name = "permission_level", nullable = false, length = 16)
    private String permissionLevel;

    /** 允许的最高风险; 动作的风险高于它即拒绝。 */
    @Column(name = "risk_ceiling", nullable = false, length = 16)
    private String riskCeiling;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public boolean expired() {
        return expiresAt != null && expiresAt.isBefore(LocalDateTime.now());
    }

    /** 覆盖某个动作: 精确命中 action_id, 或命中它所属的能力。 */
    public boolean covers(String candidateActionId, String candidateCapabilityId) {
        if (actionId != null) return actionId.equals(candidateActionId);
        return capabilityId != null && capabilityId.equals(candidateCapabilityId);
    }

    @PrePersist
    void assignId() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}

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
 * LAP v1: 一次"安装" —— <b>某个 principal 装了某个应用</b>。
 *
 * <p>没有安装就没有会话, 没有会话就没有资源, 没有授权就调不动动作。这条链
 * ({@code Application → Installation → ApplicationSession → Resource}) 是 LAP 的骨架,
 * 四条归属不变量全部围绕它。
 *
 * <p>唯一键是 {@code (application_id, principal_type, principal_id)}: 真人装一次,
 * Agent 装一次, 两者井水不犯河水 —— Agent 的安装不该给真人开权限, 反之亦然。
 */
@Entity
@Table(name = "installation",
        uniqueConstraints = @UniqueConstraint(name = "uq_installation",
                columnNames = {"application_id", "principal_type", "principal_id"}))
@Getter
@Setter
public class InstallationRecord {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUSPENDED = "SUSPENDED";
    public static final String STATUS_UNINSTALLED = "UNINSTALLED";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "application_id", nullable = false, length = 128)
    private String applicationId;

    /** 安装时钉住的版本。之后应用发新版本不影响这次安装 —— 升级是显式动作。 */
    @Column(name = "application_version_id", nullable = false, length = 36)
    private String applicationVersionId;

    @Column(name = "principal_type", nullable = false, length = 16)
    private String principalType;

    @Column(name = "principal_id", nullable = false, length = 64)
    private String principalId;

    @Column(nullable = false, length = 32)
    private String status = STATUS_ACTIVE;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public boolean active() {
        return STATUS_ACTIVE.equals(status);
    }

    @PrePersist
    void assignId() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}

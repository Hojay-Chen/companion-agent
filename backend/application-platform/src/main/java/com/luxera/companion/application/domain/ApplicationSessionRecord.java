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
 * LAP v1: <b>平台级的应用会话</b> —— 一次"打开这个应用做一件事"。
 *
 * <p>它是资源归属的锚: 棋局(应用自己的业务对象)挂在它下面, {@code resource.session_id}
 * 指向它。归属链 {@code Application → Installation → ApplicationSession → Resource}
 * 的中间两环就是这里。
 *
 * <p>"平台级"是刻意的: 会话由平台创建与回收({@code SessionReaperJob}), 应用不管理自己的
 * 会话表。于是加一个新应用不必再想一遍"会话怎么存、怎么清"。
 */
@Entity
@Table(name = "application_session")
@Getter
@Setter
public class ApplicationSessionRecord {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ENDED = "ENDED";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "application_id", nullable = false, length = 128)
    private String applicationId;

    @Column(name = "version_id", nullable = false, length = 36)
    private String versionId;

    @Column(name = "installation_id", nullable = false, length = 36)
    private String installationId;

    /** 与 installation 的 principal 必须一致 —— 归属不变量之一。 */
    @Column(name = "principal_type", nullable = false, length = 16)
    private String principalType;

    @Column(name = "principal_id", nullable = false, length = 64)
    private String principalId;

    /**
     * 与 {@code principal_id} 同为 64, 而不是 36 —— 这两个字段的值<em>直接来自</em>解析出来的
     * principal({@code companionId} / {@code userId}), 而 principal id 的合法上限就是 64
     * ({@code system:reaper} 这类系统身份、以及 MCP 客户端自报的 id 都可能超过 36)。取 36 的话
     * 会出现"身份在门口被接受、写会话时被数据库拒绝"的分裂, 那种错误最难查。
     */
    @Column(name = "companion_id", length = 64)
    private String companionId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(nullable = false, length = 32)
    private String status = STATUS_ACTIVE;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "last_active_at")
    private LocalDateTime lastActiveAt;

    public boolean active() {
        return STATUS_ACTIVE.equals(status);
    }

    @PrePersist
    void assignId() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (lastActiveAt == null) lastActiveAt = createdAt;
    }
}

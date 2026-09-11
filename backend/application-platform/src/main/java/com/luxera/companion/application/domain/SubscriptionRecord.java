package com.luxera.companion.application.domain;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * LAP v1: 订阅 —— 订阅者说"这一类资源上的这一类事件我要", 而不是轮询。
 *
 * <p>本轮({@code delivery_mode=SINK})它是<em>发射前的过滤器</em>: 事件投递前按 URI pattern
 * 与事件类型筛一遍。{@code INBOX}(持久投递、订阅者自己来取)要等 R8 的 outbox relay ——
 * 在那之前老实说它是 SINK, 不假装。
 *
 * <p><b>它不是调度器。</b> "15:00 提醒我"是应用自己的到期任务, 不是订阅 —— 混为一谈会做出
 * 一个从不提醒任何人的 Agent。
 */
@Entity
@Table(name = "subscription")
@Getter
@Setter
public class SubscriptionRecord {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "session_id", length = 36)
    private String sessionId;

    @Column(name = "principal_type", nullable = false, length = 16)
    private String principalType;

    @Column(name = "principal_id", nullable = false, length = 64)
    private String principalId;

    @Column(name = "resource_uri_pattern", nullable = false, length = 256)
    private String resourceUriPattern;

    /** 逗号分隔的事件类型; 空串表示全部。 */
    @Column(name = "event_types", length = 512)
    private String eventTypes;

    @Column(name = "delivery_mode", nullable = false, length = 16)
    private String deliveryMode = "SINK";

    @Column(nullable = false, length = 32)
    private String status = STATUS_ACTIVE;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_delivered_at")
    private LocalDateTime lastDeliveredAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public boolean active() {
        return STATUS_ACTIVE.equals(status)
                && (expiresAt == null || expiresAt.isAfter(Instant.now()));
    }

    @PrePersist
    void assignId() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}

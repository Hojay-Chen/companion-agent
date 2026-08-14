package com.luxera.companion.relationship;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 用户与伴侣的关系状态(数值主要供系统使用,不直接展示给用户)。
 * 一个 user-companion 对一条。
 */
@Entity
@Table(name = "relationships", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "companion_id"}))
@Getter
@Setter
public class Relationship {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "companion_id", nullable = false, length = 36)
    private String companionId;

    @Column(name = "relationship_type", length = 32)
    private String relationshipType = "girlfriend";

    @Column(name = "relationship_stage", nullable = false, length = 32)
    private String relationshipStage = "new";

    @Column(nullable = false)
    private double familiarity = 0.05;

    @Column(nullable = false)
    private double trust = 0.1;

    @Column(nullable = false)
    private double intimacy = 0.05;

    @Column(nullable = false)
    private double affection = 0.2;

    @Column(name = "shared_experience_count", nullable = false)
    private int sharedExperienceCount = 0;

    @Column(name = "message_count", nullable = false)
    private int messageCount = 0;

    @Column(name = "last_interaction_at")
    private LocalDateTime lastInteractionAt;

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt = LocalDateTime.now();

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}

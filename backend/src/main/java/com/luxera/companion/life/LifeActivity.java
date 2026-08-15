package com.luxera.companion.life;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/** 伴侣的生活活动(设计文档 V2.0 §5.4) */
@Entity
@Table(name = "life_activities", indexes = @Index(name = "idx_life_act_companion", columnList = "companion_id"))
@Getter
@Setter
public class LifeActivity {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "companion_id", nullable = false, length = 36)
    private String companionId;

    /** SLEEP/WORK/STUDY/MEAL/COMMUTE/EXERCISE/LEISURE/SOCIAL/HOUSEWORK/HOBBY/REST/OTHER */
    @Column(nullable = false, length = 32)
    private String type;

    @Column(nullable = false, length = 128)
    private String title;

    @Column(length = 500)
    private String description;

    @Column(name = "planned_start")
    private LocalDateTime plannedStart;

    @Column(name = "planned_end")
    private LocalDateTime plannedEnd;

    @Column(name = "actual_start")
    private LocalDateTime actualStart;

    @Column(name = "actual_end")
    private LocalDateTime actualEnd;

    @Column(nullable = false)
    private double importance = 0.4;

    @Column(name = "emotional_significance", nullable = false)
    private double emotionalSignificance = 0.3;

    /** PLANNED/ACTIVE/DONE/CANCELLED */
    @Column(nullable = false, length = 16)
    private String status = "PLANNED";

    /** SIMULATED_LIFE_EVENT / USER_SHARED_EVENT / REAL_TOOL_EVENT / SYSTEM_EVENT */
    @Column(nullable = false, length = 32)
    private String source = "SIMULATED_LIFE_EVENT";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}

package com.luxera.companion.state;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/** 伴侣短期动态状态(不等于人格) */
@Entity
@Table(name = "agent_states")
@Getter
@Setter
public class AgentState {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "companion_id", nullable = false, unique = true, length = 36)
    private String companionId;

    @Column(length = 32)
    private String mood = "calm";

    @Column(nullable = false)
    private double energy = 0.72;

    @Column(nullable = false)
    private double stress = 0.18;

    @Column(name = "social_energy", nullable = false)
    private double socialEnergy = 0.6;

    @Column(nullable = false)
    private double curiosity = 0.68;

    @Column(name = "emotional_closeness", nullable = false)
    private double emotionalCloseness = 0.3;

    /** V4 Appraisal: 受伤程度(0-1, 随状态衰减) */
    @Column(nullable = false)
    private double hurt = 0;

    /** V4 Appraisal: 生气程度(0-1, 随状态衰减) */
    @Column(nullable = false)
    private double anger = 0;

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

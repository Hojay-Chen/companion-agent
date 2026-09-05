package com.luxera.companion.tool;

import com.luxera.companion.common.convert.StringMapConverter;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/** 提醒(伴侣生日/用户生日/用户自定义) */
@Entity
@Table(name = "reminders")
@Getter
@Setter
public class Reminder {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "companion_id", nullable = false, length = 36)
    private String companionId;

    /** birthday | user_birthday | user_set | check_in */
    @Column(nullable = false, length = 32)
    private String type;

    @Column(nullable = false, length = 128)
    private String title;

    @Column(length = 500)
    private String content;

    @Column(name = "remind_at", nullable = false)
    private LocalDateTime remindAt;

    /** pending | done | cancelled */
    @Column(length = 32)
    private String status = "pending";

    @Convert(converter = StringMapConverter.class)
    @Column(columnDefinition = "text")
    private Map<String, Object> payload;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}
